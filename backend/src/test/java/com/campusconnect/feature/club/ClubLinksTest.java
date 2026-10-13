package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.error.AppException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ClubLinks} cleans links the way {@code new URL(...).toString()} did in utils/ClubLinks.js. */
class ClubLinksTest {

    static String website(String value) {
        return ClubLinks.normalizeUrl(value, "Website", null);
    }

    static void refused(String value, String message) {
        assertThatThrownBy(() -> website(value)).isInstanceOf(AppException.class).hasMessage(message);
    }

    @Test
    void addsTheSchemeAndSerializesLikeTheWhatwgParser() {
        assertThat(website("roboticsclub.ddu.ac.in")).isEqualTo("https://roboticsclub.ddu.ac.in/");
        assertThat(website("  linkedin.com/company/ddu-robotics ")).isEqualTo("https://linkedin.com/company/ddu-robotics");
        assertThat(website("HTTP://Example.COM/Path?q=1#Frag")).isEqualTo("http://example.com/Path?q=1#Frag");
        assertThat(website("https://example.com:443/a/./b/../c")).isEqualTo("https://example.com/a/c");
        assertThat(website("https://example.com:8080")).isEqualTo("https://example.com:8080/");
        assertThat(website("https://example.com/a b?x=\"y\"")).isEqualTo("https://example.com/a%20b?x=%22y%22");
        assertThat(website("https://bücher.de/über")).isEqualTo("https://xn--bcher-kva.de/%C3%BCber");
        assertThat(website("http://0x7f.1")).isEqualTo("http://127.0.0.1/");
        assertThat(website("https:\\\\example.com\\a")).isEqualTo("https://example.com/a");
        assertThat(website("https://@example.com")).isEqualTo("https://example.com/");
        assertThat(website("")).isNull();
        assertThat(ClubLinks.normalizeUrl(null, "Website", null)).isNull();
    }

    @Test
    void refusesWhatIsNotAPublicWebAddress() {
        refused("javascript:alert(1)", "Website must be a valid web address");
        refused("ftp://example.com", "Website must be a valid web address");
        refused("not a url", "Website must be a valid web address");
        refused("https://localhost", "Website must be a valid web address");
        refused("https://exa mple.com", "Website must be a valid web address");
        refused("https://example.com:99999", "Website must be a valid web address");
        refused("https://user:secret@example.com", "Website must not contain a username or password");
        refused("https://user@example.com", "Website must not contain a username or password");
        refused("https://example.com/" + "a".repeat(300), "Website link is too long");
    }

    @Test
    void socialLinksMustPointAtTheirPlatform() {
        Map<String, String> links = ClubLinks.normalizeSocialLinks(Map.of("youtube", "m.youtube.com/@club", "x", "twitter.com/club"));
        assertThat(links.keySet()).containsExactly("instagram", "linkedin", "x", "youtube", "facebook", "github", "discord", "whatsapp");
        assertThat(links).containsEntry("youtube", "https://m.youtube.com/@club").containsEntry("x", "https://twitter.com/club").containsEntry("github", null);

        assertThatThrownBy(() -> ClubLinks.normalizeSocialLinks(Map.of("youtube", "https://youtube.com.evil.example")))
                .hasMessage("YouTube link must be on youtube.com");
        assertThatThrownBy(() -> ClubLinks.normalizeSocialLinks(Map.of("myspace", "x", "tiktok", "y"))).hasMessageStartingWith("Unsupported social link: ");
        assertThatThrownBy(() -> ClubLinks.normalizeSocialLinks(List.of("https://instagram.com/a"))).hasMessage("Social links must be an object");
    }
}
