package com.campusconnect.feature.auth;

import com.campusconnect.core.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie: HttpOnly, Secure (except plain-http development), scoped to /api/auth.
 * SameSite=Strict by default (frontend and API on one origin behind Nginx); set
 * {@code app.jwt.cookie-same-site=None} when the frontend is served from another site.
 */
@Component
public class RefreshCookies {

    public static final String NAME = "refreshToken";

    private final AppProperties properties;

    public RefreshCookies(AppProperties properties) {
        this.properties = properties;
    }

    public String set(String token) {
        return base(token).maxAge(properties.getJwt().getRefreshTokenTtl()).build().toString();
    }

    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        AppProperties.Jwt jwt = properties.getJwt();
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(jwt.isCookieSecure())
                .sameSite(jwt.getCookieSameSite())
                .path("/api/auth");
    }
}
