package com.campusconnect.core.push;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.jobs.JobQueue;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.notification.PushSubscription;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Web Push: notifications on phones and laptops even when CampusConnect isn't open (services/PushService.js).
 * Sending happens in the background job queue, so callers never wait for the browser vendors.
 */
@Service
public class PushService {

    private final WebPushSender sender;
    private final MongoOperations mongo;
    private final JobQueue jobs;
    private final AppProperties properties;

    public PushService(WebPushSender sender, MongoOperations mongo, JobQueue jobs, AppProperties properties) {
        this.sender = sender;
        this.mongo = mongo;
        this.jobs = jobs;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return sender.isConfigured();
    }

    /** The public VAPID key browsers need to subscribe (null when push is switched off). */
    public String publicKey() {
        return isEnabled() ? properties.getPush().getPublicKey() : null;
    }

    public record SubscriptionKeys(String p256dh, String auth) {
    }

    public Map<String, Object> subscribe(AuthUser actor, String endpoint, SubscriptionKeys keys, String userAgent) {
        if (!isEnabled()) {
            throw AppException.of(503, "Push notifications aren't set up on this server", ErrorCode.INVALID_STATE);
        }
        String target = endpoint == null ? "" : endpoint;
        if (!target.startsWith("https://") || keys == null || isBlank(keys.p256dh()) || isBlank(keys.auth()) || !sender.isAllowedEndpoint(target)) {
            throw AppException.badRequest("This browser sent an invalid push subscription", ErrorCode.VALIDATION_ERROR);
        }
        String agent = userAgent == null ? "" : userAgent;
        Instant now = Instant.now();
        // An endpoint belongs to one browser; if another account used it before, it moves to this one.
        mongo.upsert(
                Query.query(Criteria.where("endpoint").is(target)),
                new Update()
                        .set("user", new ObjectId(actor.id()))
                        .set("keys", new PushSubscription.Keys(keys.p256dh(), keys.auth()))
                        .set("userAgent", agent.length() > 300 ? agent.substring(0, 300) : agent)
                        .set("failures", 0)
                        .set("updatedAt", now)
                        .setOnInsert("createdAt", now),
                PushSubscription.class);
        return Map.of("subscribed", true);
    }

    public Map<String, Object> unsubscribe(AuthUser actor, String endpoint) {
        mongo.remove(Query.query(Criteria.where("endpoint").is(endpoint == null ? "" : endpoint).and("user").is(new ObjectId(actor.id()))), PushSubscription.class);
        return Map.of("subscribed", false);
    }

    /** Queues one notification for every device of these users; never fails the caller. */
    public void pushToUsers(Collection<String> userIds, PushPayload payload) {
        if (!isEnabled() || userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            jobs.push(new PushJob(List.copyOf(userIds), payload));
        } catch (RuntimeException ignored) {
            // Push is best effort.
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
