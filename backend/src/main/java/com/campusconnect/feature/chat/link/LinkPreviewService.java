package com.campusconnect.feature.chat.link;

import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.chat.LinkPreview;
import com.campusconnect.feature.chat.JsValues;
import com.github.benmanes.caffeine.cache.Cache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Preview cards for links in chat (title, description, image), read from the page's Open Graph tags
 * (services/LinkPreviewService.js). Safe against server-side request forgery: every hop — the link and each redirect —
 * is resolved and refused unless all its addresses are public ({@link PublicAddresses}); at most 3 redirects, 4 s per
 * request, 256 KB of HTML, only http(s) on standard ports. Results (also "no preview") are cached for 6 hours.
 */
@Slf4j
@Service
public class LinkPreviewService {

    static final Duration TIMEOUT = Duration.ofSeconds(4);
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_REDIRECTS = 3;
    /** At most this many pages are fetched at once per replica; previews are a nicety, not worth a flood. */
    static final int MAX_CONCURRENT = 16;

    /** JavaScript's {@code \s}: ASCII white space plus the Unicode spaces, so links end where they did before. */
    private static final String JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern URL = Pattern.compile("\\bhttps?://[^" + JS_SPACE + "<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[),.!?;:]+$");
    private static final Pattern WHITESPACE = Pattern.compile("[" + JS_SPACE + "]+");

    /** One HTTP exchange: status, Location header, Content-Type and (for HTML only) the first 256 KB of the body. */
    record Hop(int status, String location, String contentType, String body) {
    }

    /** Performs one request without following redirects (the real HTTP client, or a stub in tests). */
    @FunctionalInterface
    interface Transport {
        Hop fetch(URI uri) throws IOException, InterruptedException;
    }

    private final Cache<String, Optional<LinkPreview>> cache;
    private final PublicAddresses.Resolver resolver;
    private final Transport transport;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    @Autowired
    public LinkPreviewService(LocalCaches caches) {
        this(caches, InetAddress::getAllByName, new JdkTransport());
    }

    LinkPreviewService(LocalCaches caches, PublicAddresses.Resolver resolver, Transport transport) {
        this.cache = caches.cache("chatLinkPreviews", Duration.ofHours(6), 500);
        this.resolver = resolver;
        this.transport = transport;
    }

    /** The first http(s) link in a text, without trailing punctuation. */
    public static String firstLink(String text) {
        Matcher match = URL.matcher(text == null ? "" : text);
        return match.find() ? TRAILING_PUNCTUATION.matcher(match.group()).replaceFirst("") : null;
    }

    /** A preview for the link, or null (unreachable, not HTML, private address…). Never throws. */
    public LinkPreview previewFor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Optional<LinkPreview> cached = cache.getIfPresent(raw);
        if (cached != null) {
            return cached.orElse(null);
        }
        LinkPreview preview = null;
        try {
            slots.acquire();
            try {
                preview = fetchPreview(raw);
            } finally {
                slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (PublicAddresses.UnsafeLinkException | IOException | RuntimeException e) {
            log.debug("No link preview for {}: {}", raw, e.getMessage());
        }
        cache.put(raw, Optional.ofNullable(preview));
        return preview;
    }

    LinkPreview fetchPreview(String raw) throws PublicAddresses.UnsafeLinkException, IOException, InterruptedException {
        String current = raw;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = PublicAddresses.assertPublicUrl(current, resolver);
            Hop response = transport.fetch(uri);
            if (response.status() >= 300 && response.status() < 400 && response.location() != null && !response.location().isEmpty()) {
                // Resolved against the normalised page URL: java.net.URI mis-joins relative paths onto an empty path.
                current = URI.create(pageUrl(uri)).resolve(response.location()).toString();
                continue;
            }
            boolean html = response.contentType() != null && response.contentType().contains("text/html");
            if (response.status() < 200 || response.status() >= 300 || !html) {
                return null;
            }
            return parse(response.body(), pageUrl(uri));
        }
        return null;
    }

    /** The page address as a browser writes it: lower-case scheme and host, no default port, at least "/". */
    static String pageUrl(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }

    /** Reads the card from the page's Open Graph / Twitter tags, falling back to {@code <title>}; null without a title. */
    public static LinkPreview parse(String html, String pageUrl) {
        Document page = Jsoup.parse(html == null ? "" : html, pageUrl);
        String title = metaContent(page, "og:title", "twitter:title");
        if (title.isEmpty()) {
            Element element = page.selectFirst("title");
            title = element == null ? "" : clean(element.text());
        }
        if (title.isEmpty()) {
            return null;
        }
        String description = metaContent(page, "og:description", "twitter:description", "description");
        String image = absoluteImage(metaContent(page, "og:image", "og:image:url", "twitter:image"), pageUrl);
        String site = metaContent(page, "og:site_name");
        if (site.isEmpty()) {
            String host = URI.create(pageUrl).getHost();
            site = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        }
        LinkPreview preview = new LinkPreview();
        preview.setUrl(pageUrl);
        preview.setTitle(JsValues.cut(title, 200));
        preview.setDescription(JsValues.cut(description, 300));
        preview.setImage(JsValues.cut(image, 1000));
        preview.setSite(JsValues.cut(site, 80));
        return preview;
    }

    /** The first {@code <meta>} named (property or name) like each candidate in turn, if it has content. */
    private static String metaContent(Document page, String... names) {
        for (String name : names) {
            Element tag = page.select("meta").stream()
                    .filter(meta -> name.equalsIgnoreCase(meta.attr("property")) || name.equalsIgnoreCase(meta.attr("name")))
                    .findFirst()
                    .orElse(null);
            String content = tag == null ? "" : tag.attr("content");
            if (!content.isEmpty()) {
                return clean(content);
            }
        }
        return "";
    }

    /** Images are shown to everyone, so only secure absolute URLs are kept. */
    private static String absoluteImage(String image, String pageUrl) {
        if (image.isEmpty()) {
            return "";
        }
        try {
            String absolute = URI.create(pageUrl).resolve(image).toString();
            return absolute.startsWith("https://") ? absolute : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String clean(String value) {
        return JsValues.trim(WHITESPACE.matcher(value).replaceAll(" "));
    }

    /** The JDK HTTP client: no redirects followed, no proxy, 4 s per request, the body read only for HTML pages. */
    static final class JdkTransport implements Transport {

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(TIMEOUT)
                .build();

        @Override
        public Hop fetch(URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "CampusConnectBot/1.0 (+link preview)")
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request, info -> {
                String type = info.headers().firstValue("content-type").orElse("");
                boolean html = info.statusCode() >= 200 && info.statusCode() < 300 && type.contains("text/html");
                return new LimitedBody(html ? MAX_BYTES : 0);
            });
            try {
                // The whole exchange, body included, must finish in time (a slow-dripping server is cut off).
                HttpResponse<String> response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new Hop(response.statusCode(), response.headers().firstValue("location").orElse(null),
                        response.headers().firstValue("content-type").orElse(""), response.body());
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new IOException("Timed out");
            } catch (ExecutionException e) {
                throw new IOException(e.getCause() == null ? "Request failed" : e.getCause().getMessage());
            }
        }
    }

    /** Keeps the first {@code limit} bytes of a response and then cancels the download. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<String> {

        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (limit <= 0) {
                subscription.cancel();
                finish();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                int take = Math.min(item.remaining(), limit - bytes.size());
                byte[] chunk = new byte[take];
                item.get(chunk);
                bytes.write(chunk, 0, take);
                if (bytes.size() >= limit) {
                    subscription.cancel();
                    finish();
                    return;
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            finish();
        }

        private void finish() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
