package com.campusconnect.core.cache;

import java.time.Duration;
import java.util.Map;

/**
 * Shared (Redis) Spring caches and their time-to-live. Short TTLs keep data fresh across replicas; writes evict
 * explicitly ({@code @CacheEvict} or {@link SharedCaches}).
 */
public final class CacheNames {

    /** The viewer-independent part of an events feed page (EventService.listEvents). */
    public static final String EVENT_FEED = "eventFeed";
    /** A page of the public clubs directory. */
    public static final String CLUB_DIRECTORY = "clubDirectory";
    /** Departments, batches and venues lists. */
    public static final String REFERENCE = "reference";
    /** Published results lists. */
    public static final String RESULTS = "results";

    public static final Map<String, Duration> TTL = Map.of(
            EVENT_FEED, Duration.ofSeconds(5),
            CLUB_DIRECTORY, Duration.ofSeconds(5),
            REFERENCE, Duration.ofMinutes(5),
            RESULTS, Duration.ofSeconds(15));

    private CacheNames() {
    }
}
