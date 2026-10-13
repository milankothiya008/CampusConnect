package com.campusconnect.feature.chat;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * "A few messages a second is plenty for a person; this stops scripts flooding a club group" (ChatService
 * checkRate): 25 messages, and the count is forgotten 10 s after the last accepted one. Counted in Redis so the
 * limit holds across replicas; a refused attempt is not counted. Without Redis, sending is allowed.
 */
@Slf4j
@Component
public class SendRateLimit {

    static final int MAX_MESSAGES = 25;
    static final Duration WINDOW = Duration.ofSeconds(10);

    private static final RedisScript<Long> CHECK = RedisScript.of("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0') + 1
            if used > tonumber(ARGV[1]) then return 0 end
            redis.call('SET', KEYS[1], used, 'PX', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public SendRateLimit(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String userId) {
        Long allowed;
        try {
            allowed = redis.execute(CHECK, List.of("cc:chat-send:" + userId), String.valueOf(MAX_MESSAGES), String.valueOf(WINDOW.toMillis()));
        } catch (RuntimeException e) {
            log.debug("Chat send limit unavailable: {}", e.getMessage());
            return;
        }
        if (allowed != null && allowed == 0) {
            throw AppException.of(429, "You're sending messages too quickly — wait a few seconds", ErrorCode.RATE_LIMITED);
        }
    }
}
