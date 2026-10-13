package com.campusconnect.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** The route access levels of middleware/Auth.js (protect, optionalAuth, requireVerified, restrictTo). */
class AccessPolicyIntegrationTest extends IntegrationTest {

    @Autowired
    JwtEncoder encoder;

    @Autowired
    AuthUserService authUsers;

    String expiredToken(TestUser user) {
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.id()).claim("role", user.globalRole()).issuedAt(past.minusSeconds(60)).expiresAt(past).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    void update(TestUser user, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("email").is(user.email())), update, "users");
        authUsers.evict(user.id());
    }

    @Test
    void publicRoutesIgnoreAnyToken() {
        TestUser student = data.student();
        assertThat(api.get("/api/test-probe/public").status()).isEqualTo(200);
        assertThat(api.withToken("garbage").get("/api/test-probe/public").status()).isEqualTo(200);
        Response signedIn = api.as(student).get("/api/test-probe/public");
        assertThat((Object) signedIn.at("$.data.user")).isNull();
    }

    @Test
    void optionalRoutesPersonaliseButStillRejectBadTokens() {
        TestUser student = data.student();
        assertThat((Object) api.get("/api/test-probe/optional").at("$.data.user")).isNull();
        assertThat(api.as(student).get("/api/test-probe/optional").string("$.data.user")).isEqualTo(student.id());

        Response invalid = api.withToken("not-a-jwt").get("/api/test-probe/optional");
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(invalid.errorCode()).isEqualTo("TOKEN_INVALID");
        assertThat(invalid.message()).isEqualTo("Invalid token");

        Response expired = api.withToken(expiredToken(student)).get("/api/test-probe/optional");
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.errorCode()).isEqualTo("TOKEN_EXPIRED");
        assertThat(expired.message()).isEqualTo("Session expired");
    }

    @Test
    void protectedRoutesNeedAnActiveAccountAndMostAlsoAVerifiedEmail() {
        TestUser student = data.student();
        Response anonymous = api.get("/api/test-probe/verified");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.message()).isEqualTo("Authentication required");
        assertThat(anonymous.errorCode()).isEqualTo("UNAUTHORIZED");

        update(student, new Update().set("isEmailVerified", false));
        assertThat(api.as(student).get("/api/test-probe/authenticated").status()).isEqualTo(200);
        Response unverified = api.as(student).get("/api/test-probe/verified");
        assertThat(unverified.status()).isEqualTo(403);
        assertThat(unverified.errorCode()).isEqualTo("UNVERIFIED_EMAIL");
        assertThat(unverified.message()).isEqualTo("Email verification required");

        update(student, new Update().set("isActive", false));
        Response disabled = api.as(student).get("/api/test-probe/authenticated");
        assertThat(disabled.status()).isEqualTo(401);
        assertThat(disabled.message()).isEqualTo("Authentication required");
    }

    @Test
    void roleRestrictedRoutesCheckTheGlobalRole() {
        Response student = api.as(data.student()).get("/api/test-probe/admin");
        assertThat(student.status()).isEqualTo(403);
        assertThat(student.message()).isEqualTo("You do not have permission to perform this action");
        assertThat(api.as(data.admin()).get("/api/test-probe/admin").status()).isEqualTo(200);
    }

    @Test
    void aRoleChangeAppliesAtOnceBecauseTheUserCacheIsEvicted() {
        TestUser faculty = data.faculty();
        assertThat(api.as(faculty).get("/api/test-probe/admin").status()).isEqualTo(403);
        update(faculty, new Update().set("globalRole", "ADMIN"));
        assertThat(api.as(faculty).get("/api/test-probe/admin").status()).isEqualTo(200);
    }

    @Test
    void unknownRoutesAndBrokenJsonGetTheOriginalMessages() {
        Response missing = api.get("/api/nope");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.message()).isEqualTo("Cannot find GET /api/nope on this server");

        Response malformed = api.as(data.student()).post("/api/test-probe/echo", "{not json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.message()).isEqualTo("Malformed JSON body");
        assertThat(malformed.errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void responsesCarrySecurityHeadersButNoBlanketNoStore() {
        Response health = api.get("/api/health");
        assertThat(health.header("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(health.header("X-Frame-Options")).containsExactly("DENY");
        assertThat(health.header("Cache-Control")).isEmpty();
    }
}
