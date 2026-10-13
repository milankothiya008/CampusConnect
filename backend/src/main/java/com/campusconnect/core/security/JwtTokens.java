package com.campusconnect.core.security;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * HS256 tokens (utils/Token.js): access tokens ({@code sub}, {@code role}; 15 min) signed with the access
 * secret, refresh tokens ({@code sub}, {@code jti}; 7 days) signed with the refresh secret. Refresh tokens are also
 * stored hashed and rotated on every use (see AuthService).
 */
@Component
public class JwtTokens {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtEncoder refreshEncoder;
    private final JwtDecoder refreshDecoder;
    private final AppProperties properties;

    public JwtTokens(JwtEncoder encoder, JwtDecoder decoder, AppProperties properties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        SecretKeySpec refreshKey = new SecretKeySpec(properties.getJwt().getRefreshSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.refreshEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(refreshKey));
        this.refreshDecoder = NimbusJwtDecoder.withSecretKey(refreshKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public String issueAccessToken(String userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .claim("role", role)
                .issuedAt(now)
                .expiresAt(now.plus(properties.getJwt().getAccessTokenTtl()))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public String issueRefreshToken(String userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .id(Hmacs.randomHex(32))
                .issuedAt(now)
                .expiresAt(now.plus(properties.getJwt().getRefreshTokenTtl()))
                .build();
        return refreshEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** Verifies signature and expiry; throws {@link org.springframework.security.oauth2.jwt.JwtException} otherwise. */
    public Jwt verify(String token) {
        return decoder.decode(token);
    }

    public Jwt verifyRefresh(String token) {
        return refreshDecoder.decode(token);
    }
}
