package com.campusconnect.core.security;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.error.GlobalExceptionHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless API security: bearer access tokens, no sessions, no CSRF tokens (the refresh cookie is HttpOnly,
 * SameSite and path-scoped; every other call needs the Authorization header). Route-level rules live in
 * {@link AccessPolicyInterceptor}; secure headers mirror helmet's defaults.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecretKey accessTokenKey(AppProperties properties) {
        String secret = properties.getJwt().getAccessSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.access-secret (JWT_ACCESS_SECRET) must be at least 32 bytes");
        }
        String refresh = properties.getJwt().getRefreshSecret();
        if (refresh == null || refresh.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.refresh-secret (JWT_REFRESH_SECRET) must be at least 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey accessTokenKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(accessTokenKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey accessTokenKey) {
        return NimbusJwtDecoder.withSecretKey(accessTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** BCrypt cost 12 (about 250 ms); tests lower it to keep the suite fast. */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokens tokens, AuthUserService users, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        http.csrf(csrf -> csrf.disable())
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .anonymous(a -> a.disable())
                // Every route decides its own access level (AccessPolicyInterceptor) — like Express middleware.
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(tokens, users), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(json.writeValueAsString(GlobalExceptionHandler.bodyMap(401, "Authentication required", ErrorCode.UNAUTHORIZED)));
                }))
                .headers(h -> h
                        // API responses set their own caching; Spring's blanket no-store would break cacheable reference data.
                        .cacheControl(c -> c.disable())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(15_552_000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(properties.getClientUrl().replaceAll("/$", "")));
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Content-Disposition", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset", "Retry-After"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
