package com.campusconnect.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Named in-process Caffeine caches (level 1) for values read on almost every request: the signed-in user, the
 * paused clubs, reference data. Every replica keeps its own copy; {@link CacheEvictionBus} makes all replicas drop
 * an entry when it changes.
 */
@Component
public class LocalCaches {

    private final Map<String, Cache<String, Object>> caches = new ConcurrentHashMap<>();

    /** Registers (or returns) a cache. */
    @SuppressWarnings("unchecked")
    public <V> Cache<String, V> cache(String name, Duration ttl, long maxEntries) {
        return (Cache<String, V>) (Cache<String, ?>) caches.computeIfAbsent(name, n -> Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries)
                .recordStats()
                .build());
    }

    void evictLocally(String name, String key) {
        Cache<String, Object> cache = caches.get(name);
        if (cache == null) {
            return;
        }
        if (key == null) {
            cache.invalidateAll();
        } else {
            cache.invalidate(key);
        }
    }

    public Map<String, Cache<String, Object>> all() {
        return Map.copyOf(caches);
    }
}
