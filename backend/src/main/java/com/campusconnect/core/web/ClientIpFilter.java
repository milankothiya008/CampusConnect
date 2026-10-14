package com.campusconnect.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The visitor's address when a proxy in front of the API forwards it in a header of its own. On Railway + Vercel,
 * Vercel's rewrite sends {@code X-Vercel-Forwarded-For}, and Railway's edge then replaces {@code X-Forwarded-For} with
 * Vercel's address — so without this every visitor would share a handful of Vercel addresses in the per-address
 * rate limits. Off unless {@code app.client-ip-header} names the header; values that aren't IP addresses are ignored.
 * A caller reaching the API directly could set the header too, so the per-account limits (wrong passwords, e-mailed
 * codes) never depend on it.
 */
@Component
@ConditionalOnProperty("app.client-ip-header")
public class ClientIpFilter extends OncePerRequestFilter implements Ordered {

    private final String header;

    public ClientIpFilter(@Value("${app.client-ip-header}") String header) {
        this.header = header;
    }

    @Override
    public int getOrder() {
        // Before Spring Security and everything else that reads the remote address.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String visitor = firstAddress(request.getHeader(header));
        if (visitor == null) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return visitor;
            }

            @Override
            public String getRemoteHost() {
                return visitor;
            }
        }, response);
    }

    /** The first entry of a comma-separated address list, if it is an IP literal (no DNS lookup), else null. */
    static String firstAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split(",", 2)[0].trim();
        try {
            return InetAddress.ofLiteral(first).getHostAddress();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
