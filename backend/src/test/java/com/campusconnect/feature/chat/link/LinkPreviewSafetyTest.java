package com.campusconnect.feature.chat.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.chat.LinkPreview;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The SSRF guard of link previews: what the server will and will not fetch on a user's behalf. */
class LinkPreviewSafetyTest {

    /** A DNS stub: the names the tests use, and nothing else. */
    static final PublicAddresses.Resolver DNS = host -> {
        Map<String, String> records = Map.of(
                "news.example", "93.184.216.34",
                "cdn.example", "151.101.1.69",
                "intranet.example", "10.0.0.7",
                "rebind.example", "203.0.113.9",
                "localhost", "127.0.0.1");
        String address = records.get(host);
        if (address == null) {
            throw new UnknownHostException(host);
        }
        return host.equals("rebind.example")
                ? new InetAddress[] {InetAddress.ofLiteral("93.184.216.34"), InetAddress.ofLiteral("192.168.1.20")}
                : new InetAddress[] {InetAddress.ofLiteral(address)};
    };

    /** Records every URL fetched and answers from a script. */
    static final class ScriptedTransport implements LinkPreviewService.Transport {
        final List<String> fetched = new ArrayList<>();
        final Map<String, LinkPreviewService.Hop> answers;

        ScriptedTransport(Map<String, LinkPreviewService.Hop> answers) {
            this.answers = answers;
        }

        @Override
        public LinkPreviewService.Hop fetch(URI uri) {
            fetched.add(uri.toString());
            LinkPreviewService.Hop hop = answers.get(uri.toString());
            return hop != null ? hop : new LinkPreviewService.Hop(404, null, "text/html", "");
        }
    }

    static LinkPreviewService.Hop redirect(String location) {
        return new LinkPreviewService.Hop(302, location, "text/html", "");
    }

    static LinkPreviewService.Hop page(String html) {
        return new LinkPreviewService.Hop(200, null, "text/html; charset=utf-8", html);
    }

    static LinkPreviewService service(ScriptedTransport transport) {
        return new LinkPreviewService(new LocalCaches(), DNS, transport);
    }

    @Nested
    class Addresses {

        @Test
        void privateLoopbackLinkLocalMulticastAndReservedAddressesAreRefused() {
            for (String ip : List.of("0.0.0.0", "10.0.0.1", "100.64.0.1", "100.127.255.254", "127.0.0.1", "127.8.9.10", "169.254.169.254", "172.16.0.1",
                    "172.31.255.255", "192.0.0.8", "192.0.2.10", "192.168.1.1", "198.18.0.1", "198.19.255.255", "224.0.0.1", "239.255.255.250", "240.0.0.1",
                    "255.255.255.255", "::", "::1", "fe80::1", "febf::1", "fec0::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:10.0.0.1",
                    "::ffff:169.254.169.254", "::127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::", "2001:db8::1")) {
                assertThat(PublicAddresses.isPublicAddress(ip)).as(ip).isFalse();
            }
        }

        @Test
        void publicAddressesAreAllowedAndNamesAreNotAddresses() {
            for (String ip : List.of("8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "2a00:1450:4001:80b::200e",
                    "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::")) {
                assertThat(PublicAddresses.isPublicAddress(ip)).as(ip).isTrue();
            }
            assertThat(PublicAddresses.isPublicAddress("example.com")).isFalse();
            assertThat(PublicAddresses.isPublicAddress(null)).isFalse();
        }

        @Test
        void onlyWebLinksOnStandardPortsWithoutCredentialsToPublicHostsPass() throws Exception {
            for (String link : List.of("ftp://news.example/file", "file:///etc/passwd", "gopher://news.example/", "javascript:alert(1)", "https://user:pass@news.example/",
                    "http://news.example:8080/", "https://news.example:22/", "http://127.0.0.1/", "http://[::1]/", "http://10.0.0.1/admin",
                    "http://169.254.169.254/latest/meta-data/", "http://2130706433/", "http://localhost/", "http://intranet.example/",
                    "http://rebind.example/", "http://unknown.example/", "not a link")) {
                assertThatThrownBy(() -> PublicAddresses.assertPublicUrl(link, DNS)).as(link).isInstanceOf(PublicAddresses.UnsafeLinkException.class);
            }
            assertThat(PublicAddresses.assertPublicUrl("https://news.example/story?id=1", DNS).getHost()).isEqualTo("news.example");
            assertThat(PublicAddresses.assertPublicUrl("http://news.example:80/", DNS)).isNotNull();
            assertThat(PublicAddresses.assertPublicUrl("https://93.184.216.34:443/", DNS)).isNotNull();
        }
    }

    @Nested
    class Fetching {

        @Test
        void everyRedirectHopIsCheckedAndAPrivateTargetIsNeverContacted() {
            ScriptedTransport transport = new ScriptedTransport(Map.of(
                    "https://news.example/a", redirect("http://169.254.169.254/latest/meta-data/"),
                    "https://news.example/b", redirect("https://intranet.example/wiki"),
                    "https://news.example/c", redirect("/d"),
                    "https://news.example/d", page("<title>Moved here</title>")));
            LinkPreviewService links = service(transport);

            assertThat(links.previewFor("https://news.example/a")).isNull();
            assertThat(links.previewFor("https://news.example/b")).isNull();
            assertThat(transport.fetched).containsExactly("https://news.example/a", "https://news.example/b");

            LinkPreview moved = links.previewFor("https://news.example/c");
            assertThat(moved.getTitle()).isEqualTo("Moved here");
            assertThat(moved.getUrl()).isEqualTo("https://news.example/d");
        }

        @Test
        void atMostThreeRedirectsAreFollowed() {
            ScriptedTransport transport = new ScriptedTransport(Map.of(
                    "https://news.example/1", redirect("https://news.example/2"),
                    "https://news.example/2", redirect("https://news.example/3"),
                    "https://news.example/3", redirect("https://news.example/4"),
                    "https://news.example/4", redirect("https://news.example/5"),
                    "https://news.example/5", page("<title>Too far</title>")));
            assertThat(service(transport).previewFor("https://news.example/1")).isNull();
            assertThat(transport.fetched).hasSize(4);
        }

        @Test
        void onlySuccessfulHtmlPagesMakeACardAndImagesMustBeSecure() {
            ScriptedTransport transport = new ScriptedTransport(Map.of(
                    "https://news.example/pdf", new LinkPreviewService.Hop(200, null, "application/pdf", "<title>x</title>"),
                    "https://news.example/gone", new LinkPreviewService.Hop(500, null, "text/html", "<title>Error</title>"),
                    "https://news.example/plain", page("<meta property=\"og:image\" content=\"http://cdn.example/a.png\"><title>Plain</title>"),
                    "https://news.example/og", page("<meta property=\"og:site_name\" content=\"News\"><meta name=\"twitter:title\" content=\"  Big   story \">"
                            + "<meta property=\"og:image\" content=\"https://cdn.example/b.png\">")));
            LinkPreviewService links = service(transport);
            assertThat(links.previewFor("https://news.example/pdf")).isNull();
            assertThat(links.previewFor("https://news.example/gone")).isNull();
            LinkPreview plain = links.previewFor("https://news.example/plain");
            assertThat(plain.getTitle()).isEqualTo("Plain");
            assertThat(plain.getImage()).isEmpty();
            assertThat(plain.getSite()).isEqualTo("news.example");
            LinkPreview og = links.previewFor("https://news.example/og");
            assertThat(og.getTitle()).isEqualTo("Big story");
            assertThat(og.getImage()).isEqualTo("https://cdn.example/b.png");
            assertThat(og.getSite()).isEqualTo("News");
        }

        @Test
        void resultsAreCachedIncludingNoPreview() {
            ScriptedTransport transport = new ScriptedTransport(Map.of("https://news.example/once", page("<title>Once</title>")));
            LinkPreviewService links = service(transport);
            links.previewFor("https://news.example/once");
            links.previewFor("https://news.example/once");
            links.previewFor("http://intranet.example/");
            links.previewFor("http://intranet.example/");
            assertThat(transport.fetched).containsExactly("https://news.example/once");
        }

        @Test
        void aBodyIsCutAfterTheLimitAndTheDownloadCancelled() throws Exception {
            LinkPreviewService.LimitedBody body = new LinkPreviewService.LimitedBody(10);
            AtomicBoolean cancelled = new AtomicBoolean();
            body.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
            body.onNext(List.of(ByteBuffer.wrap("<title>".getBytes()), ByteBuffer.wrap("Long page".getBytes())));
            assertThat(body.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS)).isEqualTo("<title>Lon");
            assertThat(cancelled).isTrue();
        }

        @Test
        void linksAreFoundInTextWithoutTrailingPunctuation() {
            assertThat(LinkPreviewService.firstLink("read (https://news.example/story)!")).isEqualTo("https://news.example/story");
            assertThat(LinkPreviewService.firstLink("no link here")).isNull();
            assertThat(LinkPreviewService.firstLink("HTTP://NEWS.EXAMPLE/X?a=1&b=2. ok")).isEqualTo("HTTP://NEWS.EXAMPLE/X?a=1&b=2");
        }
    }
}
