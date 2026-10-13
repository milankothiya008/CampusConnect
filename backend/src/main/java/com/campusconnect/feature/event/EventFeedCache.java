package com.campusconnect.feature.event;

import com.campusconnect.core.cache.CacheNames;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The viewer-independent part of an events feed page, shared by every replica for a few seconds (utils/Caches.js
 * eventFeedCache, kept in the shared Redis cache {@link CacheNames#EVENT_FEED}).
 *
 * <p>Node cleared the whole cache on every Event write (clearOnWrite), including each seat booked. Scanning Redis
 * for the cached pages on every booking would be the most expensive part of registering, so event writes bump a
 * generation number instead (one INCR): pages of an older generation are never read again and expire within the
 * cache's TTL. Writers elsewhere (results, clubs, venues) still clear the cache with
 * {@code SharedCaches.clearEventFeed()}, which removes every generation.
 *
 * <p>Like {@code TtlCache.remember}, concurrent misses of the same page on one replica share one database load.
 */
@Slf4j
@Component
public class EventFeedCache {

    static final String GENERATION_KEY = "cc:eventFeed:generation";

    private final Cache cache;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, CompletableFuture<String>> loading = new ConcurrentHashMap<>();

    public EventFeedCache(CacheManager cacheManager, StringRedisTemplate redis) {
        this.cache = cacheManager.getCache(CacheNames.EVENT_FEED);
        this.redis = redis;
    }

    /** The cached page for {@code key}, or {@code load}'s result, cached for the next requests. */
    public String remember(String key, Supplier<String> load) {
        String generation = generation();
        if (generation == null || cache == null) {
            return load.get();
        }
        String entry = generation + "|" + key;
        String cached = lookup(entry);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = loading.putIfAbsent(entry, mine);
        if (running != null) {
            return await(running);
        }
        try {
            String value = load.get();
            store(entry, value);
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(entry, mine);
        }
    }

    /** Called after every write to an event: cached pages stop being served at once, on every replica. */
    public void invalidate() {
        try {
            redis.opsForValue().increment(GENERATION_KEY);
        } catch (RuntimeException e) {
            // The pages then live until their short TTL ends, exactly like a missed clear.
            log.warn("Events feed cache invalidation failed: {}", e.getMessage());
        }
    }

    private String generation() {
        try {
            String value = redis.opsForValue().get(GENERATION_KEY);
            return value == null ? "0" : value;
        } catch (RuntimeException e) {
            log.warn("Events feed cache unavailable: {}", e.getMessage());
            return null;
        }
    }

    private String lookup(String entry) {
        try {
            return cache.get(entry, String.class);
        } catch (RuntimeException e) {
            log.warn("Events feed cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void store(String entry, String value) {
        try {
            cache.put(entry, value);
        } catch (RuntimeException e) {
            log.warn("Events feed cache write failed: {}", e.getMessage());
        }
    }

    private static String await(CompletableFuture<String> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
