package com.campusconnect.feature.hackathon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The JavaScript conversions and the WHATWG URL serialisation the hackathon payloads go through. Expected values were
 * produced by Node 24 ({@code String(x)}, {@code Number(x)}, {@code new URL(x).href}).
 */
class JsValuesAndLinksTest {

    @Test
    void numbersPrintLikeJavaScript() {
        assertThat(JsValues.numberToString(31.0)).isEqualTo("31");
        assertThat(JsValues.numberToString(67.0 / 3)).isEqualTo("22.333333333333332");
        assertThat(JsValues.numberToString(22.5)).isEqualTo("22.5");
        assertThat(JsValues.numberToString(1e21)).isEqualTo("1e+21");
        assertThat(JsValues.numberToString(0.1 + 0.2)).isEqualTo("0.30000000000000004");
        assertThat(JsValues.numberToString(1e-7)).isEqualTo("1e-7");
        assertThat(JsValues.numberToString(0.000001)).isEqualTo("0.000001");
        assertThat(JsValues.numberToString(100)).isEqualTo("100");
        assertThat(JsValues.numberToString(-2.5)).isEqualTo("-2.5");
        assertThat(JsValues.string(List.of(1, "a", Map.of()))).isEqualTo("1,a,[object Object]");
    }

    @Test
    void numberConvertsLikeJavaScript() {
        assertThat(JsValues.number("")).isZero();
        assertThat(JsValues.number(null)).isZero();
        assertThat(JsValues.number(" 8 ")).isEqualTo(8);
        assertThat(JsValues.number("0x1A")).isEqualTo(26);
        assertThat(JsValues.number("1e3")).isEqualTo(1000);
        assertThat(JsValues.number(List.of())).isZero();
        assertThat(JsValues.number(List.of(5))).isEqualTo(5);
        assertThat(JsValues.number(true)).isEqualTo(1);
        assertThat(JsValues.number("abc")).isNaN();
        assertThat(JsValues.number(JsValues.UNDEFINED)).isNaN();
        assertThat(JsValues.isInteger(3.0)).isTrue();
        assertThat(JsValues.isInteger(3.5)).isFalse();
    }

    @Test
    void truthinessTrimAndPropertyAccess() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("sent", null);
        assertThat(JsValues.get(object, "sent")).isNull();
        assertThat(JsValues.get(object, "missing")).isSameAs(JsValues.UNDEFINED);
        assertThat(JsValues.get("not an object", "x")).isSameAs(JsValues.UNDEFINED);
        assertThat(Arrays.asList(JsValues.truthy(""), JsValues.truthy(0), JsValues.truthy(Double.NaN), JsValues.truthy(null), JsValues.truthy(false)))
                .containsOnly(false);
        assertThat(Arrays.asList(JsValues.truthy("0"), JsValues.truthy(List.of()), JsValues.truthy(Map.of()), JsValues.truthy(-1))).containsOnly(true);
        assertThat(JsValues.trim("  Opening\t\n")).isEqualTo("Opening");
        assertThat(JsValues.text(null)).isEmpty();
        assertThat(JsValues.text(0)).isEmpty();
        assertThat(JsValues.textOrEmpty(false)).isEqualTo("false");
    }

    @Test
    void datesParseLikeTheDateConstructor() {
        assertThat(JsValues.date("2026-10-14T10:00:00.000Z")).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
        assertThat(JsValues.date("2026-10-14T15:30:00+05:30")).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
        assertThat(JsValues.date("2026-10-14")).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(JsValues.date(1_700_000_000_000L)).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(JsValues.date("Tue, 14 Oct 2026 10:00:00 GMT")).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
        assertThat(JsValues.date("not a date")).isNull();
        assertThat(JsValues.date(JsValues.UNDEFINED)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "https://quickbite.example.com|https://quickbite.example.com/",
        "https://github.com/beta/parkit|https://github.com/beta/parkit",
        "HTTPS://GitHub.COM/User/Repo|https://github.com/User/Repo",
        "https:example.com|https://example.com/",
        "https:///example.com//a/../b/./c/|https://example.com//b/c/",
        "https://a.com:443/|https://a.com/",
        "http://a.com:80/x|http://a.com/x",
        "http://a.com:0080/x|http://a.com/x",
        "http://a.com:/x|http://a.com/x",
        "http://user:pa ss@a.com/x|http://user:pa%20ss@a.com/x",
        "http://127.1/|http://127.0.0.1/",
        "http://0x7f.0.0.1/|http://127.0.0.1/",
        "http://[::1]:8080/|http://[::1]:8080/",
        "http://a.com/%2e%2E/x|http://a.com/x",
        "https://a.com?x|https://a.com/?x",
        "https://a.com#|https://a.com/#",
        "https://ÜBER.com/|https://xn--ber-goa.com/",
        "https://a.com/über/ü?ü=ü#ü|https://a.com/%C3%BCber/%C3%BC?%C3%BC=%C3%BC#%C3%BC",
        "https://a_b.com/|https://a_b.com/",
        "https://a..com/|https://a..com/"
    })
    void httpLinksSerialiseLikeTheUrlStandard(String input, String href) {
        WebLinks.Parsed parsed = WebLinks.parse(input);
        assertThat(parsed).isNotNull();
        assertThat(parsed.isHttp()).isTrue();
        assertThat(parsed.href()).isEqualTo(href);
    }

    @Test
    void otherLinksAreRecognisedOrRefusedLikeNode() {
        assertThat(WebLinks.parse("https://a.com/a^b{c}`d|e\"f<g>h").href()).isEqualTo("https://a.com/a%5Eb%7Bc%7D%60d|e%22f%3Cg%3Eh");
        assertThat(WebLinks.parse("https://a.com/x?q=a^b{c}`d|e\"f<g>h%zz &x=1'").href()).isEqualTo("https://a.com/x?q=a^b{c}`d|e%22f%3Cg%3Eh%zz%20&x=1%27");
        assertThat(WebLinks.parse("https://a.com/x#a^b{c}`d|e\"f<g>h #").href()).isEqualTo("https://a.com/x#a^b{c}%60d|e%22f%3Cg%3Eh%20#");
        assertThat(WebLinks.parse("https://a.com/\ttab\nnl").href()).isEqualTo("https://a.com/tabnl");
        for (String broken : List.of("example.com", "http://", "http://a b.com/", "http://a.com:99999/x", "ftp://")) {
            assertThat(WebLinks.parse(broken)).as(broken).isNull();
        }
        for (String other : List.of("ftp://nope.example", "mailto:x@y.com", "javascript:alert(1)")) {
            assertThat(WebLinks.parse(other)).as(other).isNotNull().extracting(WebLinks.Parsed::isHttp).isEqualTo(false);
        }
        assertThat(HackathonEntries.cleanUrl("  ", "live demo link", false)).isEmpty();
        assertThat(HackathonEntries.cleanUrl("https://x.dev", "live demo link", false)).isEqualTo("https://x.dev/");
    }
}
