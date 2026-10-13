package com.campusconnect.core.access;

import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Suspended or archived clubs (ClubStatusService.pausedClubIds / isPaused): their events and drives are left out of
 * listings. Read on most list requests, so the ids are cached for 30 s per replica; every club status change must
 * call {@link #evict()}.
 */
@Component
public class PausedClubs {

    public static final List<ClubStatus> PAUSED = List.of(ClubStatus.SUSPENDED, ClubStatus.ARCHIVED);
    static final String CACHE = "pausedClubs";

    private final MongoOperations mongo;
    private final CacheEvictionBus evictionBus;
    private final Cache<String, Set<String>> cache;

    public PausedClubs(MongoOperations mongo, LocalCaches caches, CacheEvictionBus evictionBus) {
        this.mongo = mongo;
        this.evictionBus = evictionBus;
        this.cache = caches.cache(CACHE, Duration.ofSeconds(30), 1);
    }

    public Set<String> ids() {
        return cache.get("ids", key -> {
            Query query = Query.query(Criteria.where("status").in(PAUSED.stream().map(Enum::name).toList()));
            query.fields().include("_id");
            return mongo.find(query, Document.class, "clubs").stream().map(club -> club.getObjectId("_id").toHexString()).collect(Collectors.toUnmodifiableSet());
        });
    }

    public static boolean isPaused(Club club) {
        return club != null && PAUSED.contains(club.getStatus());
    }

    public static boolean isPaused(ClubStatus status) {
        return PAUSED.contains(status);
    }

    public void evict() {
        evictionBus.evictAll(CACHE);
    }
}
