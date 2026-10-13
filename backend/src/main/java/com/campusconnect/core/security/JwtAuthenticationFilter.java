package com.campusconnect.core.security;

import com.campusconnect.core.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer} token. A valid token for an active user becomes the security principal
 * ({@link AuthUser}). A bad token does not fail here: the problem is remembered and {@link AccessPolicyInterceptor}
 * decides, knowing the route — so public routes ignore stale tokens exactly like the Express app.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link TokenProblem} of a rejected token. */
    public static final String TOKEN_PROBLEM = JwtAuthenticationFilter.class.getName() + ".problem";

    public record TokenProblem(String message, ErrorCode code) {
    }

    private final JwtTokens tokens;
    private final AuthUserService users;

    public JwtAuthenticationFilter(JwtTokens tokens, AuthUserService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authenticate(request, header.substring(7).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        Jwt jwt;
        try {
            jwt = tokens.verify(token);
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream().anyMatch(error -> String.valueOf(error.getDescription()).contains("expired"));
            request.setAttribute(TOKEN_PROBLEM, expired ? new TokenProblem("Session expired", ErrorCode.TOKEN_EXPIRED) : new TokenProblem("Invalid token", ErrorCode.TOKEN_INVALID));
            return;
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(TOKEN_PROBLEM, new TokenProblem("Invalid token", ErrorCode.TOKEN_INVALID));
            return;
        }
        AuthUser user = users.find(jwt.getSubject()).orElse(null);
        if (user == null || !user.active()) {
            request.setAttribute(TOKEN_PROBLEM, new TokenProblem("Authentication required", ErrorCode.UNAUTHORIZED));
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.globalRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
