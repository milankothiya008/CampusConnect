package com.campusconnect.core.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Sent / failed e-mail counts of the last 24 hours (hourly Redis counters shared by all replicas). */
@Slf4j
@Component
public class MailStats {

    private static final Duration KEEP = Duration.ofHours(26);

    private final StringRedisTemplate redis;

    public MailStats(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void sent() {
        increment("sent");
    }

    public void failed() {
        increment("failed");
    }

    public long sentLastDay() {
        return lastDay("sent");
    }

    public long failedLastDay() {
        return lastDay("failed");
    }

    private void increment(String kind) {
        String key = key(kind, Instant.now().getEpochSecond() / 3600);
        try {
            redis.opsForValue().increment(key);
            redis.expire(key, KEEP);
        } catch (RuntimeException e) {
            log.debug("Mail stats unavailable: {}", e.getMessage());
        }
    }

    private long lastDay(String kind) {
        long hour = Instant.now().getEpochSecond() / 3600;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            keys.add(key(kind, hour - i));
        }
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            return values == null ? 0 : values.stream().filter(value -> value != null).mapToLong(Long::parseLong).sum();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String key(String kind, long hour) {
        return "cc:mail:" + kind + ":" + hour;
    }
}
