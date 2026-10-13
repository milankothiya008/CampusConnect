package com.campusconnect.core.security;

import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.user.User;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Service;

/**
 * Loads the signed-in user for each request from a short-lived per-replica cache (middleware/Auth.js loadUser).
 * Any change to a user must call {@link #evict(String)} so role changes and deactivation apply at once on every
 * replica.
 */
@Service
public class AuthUserService {

    static final String CACHE = "authUsers";

    private final MongoOperations mongo;
    private final CacheEvictionBus evictionBus;
    private final Cache<String, Optional<AuthUser>> cache;

    public AuthUserService(MongoOperations mongo, LocalCaches caches, CacheEvictionBus evictionBus) {
        this.mongo = mongo;
        this.evictionBus = evictionBus;
        this.cache = caches.cache(CACHE, Duration.ofSeconds(30), 100_000);
    }

    /** The user, or empty when the id is unknown. */
    public Optional<AuthUser> find(String userId) {
        if (userId == null || !ObjectId.isValid(userId)) {
            return Optional.empty();
        }
        return cache.get(userId, id -> Optional.ofNullable(mongo.findById(id, User.class)).map(AuthUser::of));
    }

    public void evict(String userId) {
        if (userId != null) {
            evictionBus.evict(CACHE, userId);
        }
    }

    public void evictAll() {
        evictionBus.evictAll(CACHE);
    }
}
