package com.campusconnect.core.cache;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-replica invalidation for {@link LocalCaches}: evicts locally and announces the eviction on a Redis
 * pub/sub channel so every other replica drops its copy too (replaces the Mongoose "clearOnWrite" hooks of the
 * single-process Node app).
 */
@Slf4j
@Component
public class CacheEvictionBus implements MessageListener {

    public static final String CHANNEL = "cc:cache-evict";
    private static final String ALL = "*";

    private final String instanceId = UUID.randomUUID().toString();
    private final LocalCaches caches;
    private final StringRedisTemplate redis;

    public CacheEvictionBus(LocalCaches caches, StringRedisTemplate redis) {
        this.caches = caches;
        this.redis = redis;
    }

    /** Drops one entry everywhere. */
    public void evict(String cache, String key) {
        caches.evictLocally(cache, key);
        publish(cache, key == null ? ALL : key);
    }

    /** Drops the whole cache everywhere. */
    public void evictAll(String cache) {
        caches.evictLocally(cache, null);
        publish(cache, ALL);
    }

    private void publish(String cache, String key) {
        try {
            redis.convertAndSend(CHANNEL, instanceId + "|" + cache + "|" + key);
        } catch (RuntimeException e) {
            // Other replicas fall back to their short TTL.
            log.warn("Cache eviction broadcast failed: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String[] parts = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8).split("\\|", 3);
        if (parts.length != 3 || parts[0].equals(instanceId)) {
            return;
        }
        caches.evictLocally(parts[1], ALL.equals(parts[2]) ? null : parts[2]);
    }
}
