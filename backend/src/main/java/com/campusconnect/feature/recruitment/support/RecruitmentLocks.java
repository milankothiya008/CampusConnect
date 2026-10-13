package com.campusconnect.feature.recruitment.support;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Short Redis locks (like venue bookings) for the two "check, then take" steps that span several documents and must
 * hold across every replica: a role's seats (offers to the reserve list and acceptances count the seats and take one
 * under it, so a role never gets more members than openings) and a club's one drive at a time. A lock expires on its
 * own if a process dies holding it.
 */
@Component
public class RecruitmentLocks {

    static final Duration LOCK_TTL = Duration.ofSeconds(10);
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public RecruitmentLocks(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public <T> T withPositionLock(String positionId, Supplier<T> task) {
        return withLock("cc:lock:recruitment-seats:" + positionId, "Seats for this role are being updated right now. Please try again.", task);
    }

    public <T> T withClubLock(String clubId, Supplier<T> task) {
        return withLock("cc:lock:recruitment-club:" + clubId, "This club's recruitment is being updated right now. Please try again.", task);
    }

    private <T> T withLock(String key, String busy, Supplier<T> task) {
        String token = UUID.randomUUID().toString();
        long giveUpAt = System.currentTimeMillis() + LOCK_WAIT.toMillis();
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, LOCK_TTL))) {
            if (System.currentTimeMillis() > giveUpAt) {
                throw AppException.conflict(busy, ErrorCode.INVALID_STATE);
            }
            try {
                Thread.sleep(20 + ThreadLocalRandom.current().nextInt(40));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        try {
            return task.get();
        } finally {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ignored) {
                // The lock expires by itself.
            }
        }
    }
}
