package com.campusconnect.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Port of tests/unit/authUtils.test.js. */
class TokenUtilitiesTest {

    static JwtTokens tokens() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setAccessSecret("test-access-secret-that-is-long-enough-for-hs256");
        properties.getJwt().setRefreshSecret("test-refresh-secret-that-is-long-enough-for-hs256");
        SecretKeySpec key = new SecretKeySpec(properties.getJwt().getAccessSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new JwtTokens(new NimbusJwtEncoder(new ImmutableSecret<>(key)), NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(), properties);
    }

    @Test
    void hashesPasswordsAndVerifiesThem() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        PasswordHasher hasher = new PasswordHasher(encoder, 0);
        String hashed = hasher.hash("SuperSecret123!");
        assertThat(hashed).isNotEqualTo("SuperSecret123!");
        assertThat(hasher.matches("SuperSecret123!", hashed)).isTrue();
        assertThat(hasher.matches("WrongPassword", hashed)).isFalse();
    }

    @Test
    void signsAndVerifiesAccessTokens() {
        JwtTokens tokens = tokens();
        Jwt decoded = tokens.verify(tokens.issueAccessToken("user123", "STUDENT"));
        assertThat(decoded.getSubject()).isEqualTo("user123");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("STUDENT");
    }

    @Test
    void signsAndVerifiesRefreshTokensWithTheirOwnSecret() {
        JwtTokens tokens = tokens();
        String refresh = tokens.issueRefreshToken("user123");
        assertThat(tokens.verifyRefresh(refresh).getSubject()).isEqualTo("user123");
        assertThat(tokens.verifyRefresh(refresh).getId()).hasSize(64);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokens.verify(refresh)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void hashesRawTokensConsistently() {
        String raw = Hmacs.randomHex(32);
        assertThat(raw).hasSize(64);
        assertThat(Hmacs.sha256Hex(raw)).isEqualTo(Hmacs.sha256Hex(raw));
    }
}
