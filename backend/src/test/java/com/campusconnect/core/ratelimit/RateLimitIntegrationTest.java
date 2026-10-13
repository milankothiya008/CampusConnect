package com.campusconnect.core.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

/** Distributed fixed-window limits (middleware/RateLimiter.js), counted in Redis. */
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.auth=3", "app.rate-limit.api=6", "app.rate-limit.sign-in-failures=3"})
class RateLimitIntegrationTest extends IntegrationTest {

    Response from(String ip, String url, TestUser user) {
        return api.as(user).send(user == null ? HttpMethod.POST : HttpMethod.GET, url, null, request -> request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    @Test
    void signInRoutesAreLimitedPerAddress() {
        for (int i = 0; i < 3; i++) {
            assertThat(from("10.1.1.1", "/api/test-probe/auth-limited", null).status()).isEqualTo(200);
        }
        Response limited = from("10.1.1.1", "/api/test-probe/auth-limited", null);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.message()).isEqualTo("Too many authentication attempts. Please try again later.");
        assertThat(limited.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat((Boolean) limited.at("$.success")).isFalse();
        assertThat(limited.header("Retry-After")).isNotEmpty();
        assertThat(limited.header("RateLimit-Remaining")).containsExactly("0");

        // Another address has its own budget.
        assertThat(from("10.1.1.2", "/api/test-probe/auth-limited", null).status()).isEqualTo(200);
    }

    Response signIn(String ip, String email, String password) {
        return api.send(HttpMethod.POST, "/api/auth/login", Map.of("email", email, "password", password), request -> request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    @Test
    void wrongPasswordsAreCountedPerAccountWhateverTheAddress() {
        TestUser student = data.student();
        // Each guess from a new address, so the per-address limit never applies.
        for (int i = 0; i < 3; i++) {
            Response wrong = signIn("10.3.0." + i, student.email(), "Wrong#2026");
            assertThat(wrong.status()).as(wrong.body()).isEqualTo(401);
        }
        Response blocked = signIn("10.3.1.1", student.email(), TestData.PASSWORD);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.message()).isEqualTo("Too many wrong passwords for this account. Try again in a few minutes or reset your password.");
        assertThat(blocked.errorCode()).isEqualTo("RATE_LIMITED");

        // Other accounts are unaffected, and a right password never counts.
        TestUser other = data.student();
        for (int i = 0; i < 2; i++) {
            assertThat(signIn("10.3.2." + i, other.email(), TestData.PASSWORD).status()).isEqualTo(200);
        }
        assertThat(signIn("10.3.2.9", other.email(), "Wrong#2026").status()).isEqualTo(401);
        assertThat(signIn("10.3.2.8", other.email(), TestData.PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void signedInPeopleAreCountedOneByOneEvenBehindOneAddress() {
        TestUser first = data.student();
        TestUser second = data.student();
        for (int i = 0; i < 6; i++) {
            assertThat(from("10.2.2.2", "/api/test-probe/verified", first).status()).isEqualTo(200);
        }
        Response limited = from("10.2.2.2", "/api/test-probe/verified", first);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.message()).isEqualTo("Too many requests. Please slow down and try again shortly.");

        // Same campus NAT address, another person: unaffected.
        assertThat(from("10.2.2.2", "/api/test-probe/verified", second).status()).isEqualTo(200);
    }
}
