package com.campusconnect.core.ratelimit;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AccessPolicyInterceptor;
import com.campusconnect.core.security.AuthUser;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Distributed rate limits (Bucket4j buckets in Redis, shared by every replica), fixed windows like
 * express-rate-limit. Signed-in requests count per person — a whole campus behind one NAT address must not share
 * a quota — anonymous ones per IP. Fails open if Redis is unreachable.
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProxyManager<byte[]> buckets;
    private final AppProperties.RateLimit settings;

    public RateLimitInterceptor(ProxyManager<byte[]> buckets, AppProperties properties) {
        this.buckets = buckets;
        this.settings = properties.getRateLimit();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!settings.isEnabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        AuthUser user = AccessPolicyInterceptor.currentUser();
        String ip = request.getRemoteAddr();
        String who = user != null ? "u:" + user.id() : "ip:" + ip;

        if (!consume(response, "api:" + who, settings.getApi(), "Too many requests. Please slow down and try again shortly.")) {
            return false;
        }
        RateLimited extra = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
        if (extra == null) {
            extra = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimited.class);
        }
        if (extra == null) {
            return true;
        }
        return switch (extra.value()) {
            case AUTH -> consume(response, "auth:ip:" + ip, settings.getAuth(), "Too many authentication attempts. Please try again later.");
            case UPLOAD -> consume(response, "upload:" + who, settings.getUpload(), "Too many uploads. Please try again later.");
        };
    }

    private boolean consume(HttpServletResponse response, String key, int limit, String message) throws IOException {
        Duration window = settings.getWindow();
        ConsumptionProbe probe;
        try {
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(l -> l.capacity(limit).refillIntervally(limit, window))
                    .build();
            probe = buckets.builder().build(("cc:rl:" + key).getBytes(StandardCharsets.UTF_8), () -> configuration).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
        long resetSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForReset()).toSeconds());
        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
        response.setHeader("RateLimit-Reset", String.valueOf(resetSeconds));
        if (probe.isConsumed()) {
            return true;
        }
        response.setHeader("Retry-After", String.valueOf(Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds())));
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("errorCode", ErrorCode.RATE_LIMITED.name());
        response.getWriter().write(JSON.writeValueAsString(body));
        return false;
    }
}
