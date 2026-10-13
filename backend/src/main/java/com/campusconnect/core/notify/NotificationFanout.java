package com.campusconnect.core.notify;

import com.campusconnect.core.push.PushPayload;
import com.campusconnect.core.push.PushService;
import com.campusconnect.domain.notification.Notification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Executes a {@link BroadcastJob}: one notification per active, verified user, inserted 1,000 at a time. */
@Component
public class NotificationFanout {

    static final int BATCH = 1000;

    private final MongoOperations mongo;
    private final PushService push;

    public NotificationFanout(MongoOperations mongo, PushService push) {
        this.mongo = mongo;
        this.push = push;
    }

    public void deliver(BroadcastJob job) {
        Set<String> excluded = new HashSet<>(job.exclude() == null ? List.of() : job.exclude());
        Query query = Query.query(Criteria.where("isActive").is(true).and("isEmailVerified").is(true));
        query.fields().include("_id");
        List<String> recipients = mongo.find(query, Document.class, "users").stream()
                .map(user -> user.getObjectId("_id").toHexString())
                .filter(id -> !excluded.contains(id))
                .toList();

        for (int start = 0; start < recipients.size(); start += BATCH) {
            List<Notification> batch = new ArrayList<>();
            for (String user : recipients.subList(start, Math.min(recipients.size(), start + BATCH))) {
                Notification notification = new Notification();
                notification.setUser(user);
                notification.setType(job.type());
                notification.setTitle(job.title());
                notification.setMessage(job.message() == null ? "" : job.message());
                notification.setLink(job.link());
                batch.add(notification);
            }
            mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Notification.class).insert(batch).execute();
        }

        List<String> pushed = (job.pushTo() == null ? List.<String>of() : job.pushTo()).stream().filter(id -> !excluded.contains(id)).toList();
        push.pushToUsers(pushed, PushPayload.of(job.title(), job.message(), job.link(), job.type().name()));
    }
}
