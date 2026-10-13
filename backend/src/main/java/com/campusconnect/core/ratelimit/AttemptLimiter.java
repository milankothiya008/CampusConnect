package com.campusconnect.core.ratelimit;

import com.campusconnect.core.config.AppProperties;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Budgets keyed by what a request is about rather than who sends it — such as wrong passwords for one account,
 * whichever address they come from — in the same Redis buckets and window as the request limits. Spending is
 * separate from checking, so only failed attempts count. Fails open if Redis is unreachable, and allows
 * everything while rate limiting is switched off.
 */
@Slf4j
@Component
public class AttemptLimiter {

    private final ProxyManager<byte[]> buckets;
    private final AppProperties.RateLimit settings;

    public AttemptLimiter(ProxyManager<byte[]> rateLimitBuckets, AppProperties properties) {
        this.buckets = rateLimitBuckets;
        this.settings = properties.getRateLimit();
    }

    /** True once {@code limit} attempts have been spent under {@code key} in the current window. */
    public boolean exhausted(String key, int limit) {
        if (!settings.isEnabled()) {
            return false;
        }
        try {
            return bucket(key, limit).getAvailableTokens() <= 0;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing attempt: {}", e.getMessage());
            return false;
        }
    }

    /** Spends one attempt under {@code key}. */
    public void spend(String key, int limit) {
        if (!settings.isEnabled()) {
            return;
        }
        try {
            bucket(key, limit).tryConsume(1);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, attempt not counted: {}", e.getMessage());
        }
    }

    private BucketProxy bucket(String key, int limit) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(l -> l.capacity(limit).refillIntervally(limit, settings.getWindow()))
                .build();
        return buckets.builder().build(("cc:rl:" + key).getBytes(StandardCharsets.UTF_8), () -> configuration);
    }
}
