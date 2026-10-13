package com.campusconnect.core.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/** Programmatic eviction of the shared (Redis) Spring caches — for writes that happen in many places. */
@Component
@RequiredArgsConstructor
public class SharedCaches {

    private final CacheManager cacheManager;

    public void clear(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            try {
                cache.clear();
            } catch (RuntimeException ignored) {
                // A Redis hiccup must not fail the write; the short TTL bounds staleness.
            }
        }
    }

    /** Events, results, clubs and venues feed the events feed (the Node clearOnWrite hooks). */
    public void clearEventFeed() {
        clear(CacheNames.EVENT_FEED);
    }

    public void clearClubDirectory() {
        clear(CacheNames.CLUB_DIRECTORY);
    }
}
