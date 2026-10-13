package com.campusconnect.feature.gallery;

import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.event.GalleryStatus;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;

/**
 * Which events have approved photos or videos, most recently updated first — the order of the Gallery section. The
 * grouping reads every approved item, so each replica keeps the result for up to 30 seconds; any change to the
 * approved items drops it on every replica.
 */
@Component
public class GalleryActivity {

    static final String CACHE = "galleryActivity";
    private static final String KEY = "events";

    private final MongoOperations mongo;
    private final CacheEvictionBus evictionBus;
    private final Cache<String, List<ObjectId>> cache;

    public GalleryActivity(MongoOperations mongo, LocalCaches caches, CacheEvictionBus evictionBus) {
        this.mongo = mongo;
        this.evictionBus = evictionBus;
        this.cache = caches.cache(CACHE, Duration.ofSeconds(30), 1);
    }

    List<ObjectId> eventsWithPhotos() {
        return cache.get(KEY, key -> List.copyOf(mongo.getCollection("eventmedias").aggregate(List.of(
                        new Document("$match", new Document("status", GalleryStatus.APPROVED.name())),
                        new Document("$group", new Document("_id", "$event").append("latest", new Document("$max", "$createdAt"))),
                        new Document("$sort", new Document("latest", -1))))
                .into(new ArrayList<>()).stream().map(row -> row.getObjectId("_id")).filter(Objects::nonNull).toList()));
    }

    /** Approved items were added, approved or removed. */
    void changed() {
        evictionBus.evictAll(CACHE);
    }
}
