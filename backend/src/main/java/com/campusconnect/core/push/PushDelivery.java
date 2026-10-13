package com.campusconnect.core.push;

import com.campusconnect.domain.notification.PushSubscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one push job to every device of its users, 20 at a time on virtual threads, then records the outcome
 * in three bulk writes: successes reset the failure count, subscriptions the browser dropped (404/410) or that
 * failed 5 times in a row are deleted.
 */
@Slf4j
@Component
public class PushDelivery {

    static final int CONCURRENCY = 20;
    static final int MAX_FAILURES = 5;

    private enum Outcome { SENT, GONE, FAILED }

    private final WebPushSender sender;
    private final MongoOperations mongo;
    private final JsonMapper json;

    public PushDelivery(WebPushSender sender, MongoOperations mongo, JsonMapper json) {
        this.sender = sender;
        this.mongo = mongo;
        this.json = json;
    }

    public void deliver(PushJob job) {
        if (!sender.isConfigured() || job.userIds() == null || job.userIds().isEmpty()) {
            return;
        }
        List<ObjectId> users = job.userIds().stream().filter(ObjectId::isValid).distinct().map(ObjectId::new).toList();
        List<PushSubscription> subscriptions = mongo.find(Query.query(Criteria.where("user").in(users)), PushSubscription.class);
        if (subscriptions.isEmpty()) {
            return;
        }
        String payload = json.writeValueAsString(job.payload().normalized());

        Semaphore slots = new Semaphore(CONCURRENCY);
        List<Future<Outcome>> results = new ArrayList<>(subscriptions.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PushSubscription subscription : subscriptions) {
                results.add(executor.submit(() -> {
                    slots.acquire();
                    try {
                        return sendOne(subscription, payload);
                    } finally {
                        slots.release();
                    }
                }));
            }
        }

        List<ObjectId> sent = new ArrayList<>();
        List<ObjectId> gone = new ArrayList<>();
        List<ObjectId> failed = new ArrayList<>();
        for (int i = 0; i < subscriptions.size(); i++) {
            ObjectId id = new ObjectId(subscriptions.get(i).getId());
            Outcome outcome;
            try {
                outcome = results.get(i).get();
            } catch (Exception e) {
                outcome = Outcome.FAILED;
            }
            if (outcome == Outcome.FAILED && subscriptions.get(i).getFailures() + 1 >= MAX_FAILURES) {
                outcome = Outcome.GONE;
            }
            switch (outcome) {
                case SENT -> sent.add(id);
                case GONE -> gone.add(id);
                case FAILED -> failed.add(id);
            }
        }
        Instant now = Instant.now();
        if (!sent.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(sent)), new Update().set("lastSuccessAt", now).set("failures", 0).set("updatedAt", now), PushSubscription.class);
        }
        if (!failed.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(failed)), new Update().inc("failures", 1).set("updatedAt", now), PushSubscription.class);
        }
        if (!gone.isEmpty()) {
            mongo.remove(Query.query(Criteria.where("_id").in(gone)), PushSubscription.class);
        }
    }

    private Outcome sendOne(PushSubscription subscription, String payload) {
        try {
            int status = sender.send(subscription.getEndpoint(), subscription.getKeys().getP256dh(), subscription.getKeys().getAuth(), payload);
            if (status >= 200 && status < 300) {
                return Outcome.SENT;
            }
            // 404/410: the browser dropped the subscription (app uninstalled, permission revoked).
            return status == 404 || status == 410 ? Outcome.GONE : Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        } catch (Exception e) {
            log.debug("Push send failed: {}", e.getMessage());
            return Outcome.FAILED;
        }
    }
}
