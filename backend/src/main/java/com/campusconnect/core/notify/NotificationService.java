package com.campusconnect.core.notify;

import com.campusconnect.core.jobs.JobQueue;
import com.campusconnect.core.mail.EmailComposer;
import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.mail.MailService;
import com.campusconnect.core.mail.Recipient;
import com.campusconnect.core.push.PushPayload;
import com.campusconnect.core.push.PushService;
import com.campusconnect.domain.notification.Notification;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * In-app notifications, phone pushes and e-mails (services/NotificationService.js). Notifications are a side
 * effect of a state change: a failure here is logged and never undoes the change itself.
 */
@Slf4j
@Service
public class NotificationService {

    private final MongoOperations mongo;
    private final PushService push;
    private final MailService mail;
    private final EmailComposer composer;
    private final JobQueue jobs;

    public NotificationService(MongoOperations mongo, PushService push, MailService mail, EmailComposer composer, JobQueue jobs) {
        this.mongo = mongo;
        this.push = push;
        this.mail = mail;
        this.composer = composer;
        this.jobs = jobs;
    }

    /** Notifies these people (duplicates and excluded ids removed), optionally by push and e-mail too. */
    public void notify(Collection<String> userIds, Notice notice) {
        Set<String> excluded = ids(notice.getExclude());
        List<String> recipients = ids(userIds).stream().filter(id -> !excluded.contains(id)).toList();
        if (recipients.isEmpty()) {
            return;
        }
        try {
            mongo.insert(recipients.stream().map(user -> notification(user, notice.getType().name(), notice)).toList(), Notification.class);

            List<String> pushed = notice.getPush().all()
                    ? recipients
                    : ids(notice.getPush().only()).stream().filter(recipients::contains).toList();
            if (!pushed.isEmpty()) {
                push.pushToUsers(pushed, PushPayload.of(notice.getTitle(), notice.getMessage(), notice.getLink(), notice.getType().name()));
            }
            if (notice.isEmail()) {
                emailUsers(recipients, EmailRequest.builder()
                        .category(notice.getEmailCategory())
                        .compose(user -> EmailContent.builder()
                                .subject(notice.getTitle())
                                .heading(notice.getTitle())
                                .paragraphs(notice.getMessage() == null || notice.getMessage().isEmpty() ? List.of() : List.of(notice.getMessage()))
                                .action(notice.getLink() != null ? new EmailContent.Action("Open in CampusConnect", notice.getLink()) : null)
                                .build())
                        .build());
            }
        } catch (RuntimeException e) {
            log.error("Failed to create notifications ({}): {}", notice.getType(), e.getMessage());
        }
    }

    public void notify(String userId, Notice notice) {
        notify(userId == null ? List.of() : List.of(userId), notice);
    }

    /** Every active, verified user gets it in-app (fanned out in the background, 1,000 per insert). */
    public void notifyAllUsers(Broadcast broadcast) {
        try {
            jobs.broadcast(new BroadcastJob(broadcast.getType(), broadcast.getTitle(), broadcast.getMessage(), broadcast.getLink(),
                    List.copyOf(ids(broadcast.getExclude())), List.copyOf(ids(broadcast.getPushTo()))));
        } catch (RuntimeException e) {
            log.error("Failed to broadcast notifications ({}): {}", broadcast.getType(), e.getMessage());
        }
    }

    /**
     * Queues one personal e-mail per recipient. Inactive or unverified accounts, and anyone who switched the
     * category off, are skipped. Returns how many e-mails were queued.
     */
    public int emailUsers(Collection<String> userIds, EmailRequest request) {
        Set<String> excluded = ids(request.getExclude());
        List<ObjectId> recipients = ids(userIds).stream().filter(id -> !excluded.contains(id)).map(ObjectId::new).toList();
        if (recipients.isEmpty()) {
            return 0;
        }
        try {
            Criteria filter = Criteria.where("_id").in(recipients).and("isActive").is(true).and("isEmailVerified").is(true);
            if (request.getCategory() != null && request.getCategory().isOptional()) {
                filter = filter.and("emailPreferences." + request.getCategory().key()).ne(false);
            }
            Query query = Query.query(filter);
            query.fields().include("name").include("email");
            List<MailJob> batch = new ArrayList<>();
            for (Document user : mongo.find(query, Document.class, "users")) {
                Recipient recipient = new Recipient(user.getObjectId("_id").toHexString(), user.getString("name"), user.getString("email"));
                EmailContent content = request.getCompose().apply(recipient);
                if (content.getCategory() == null) {
                    content = content.toBuilder().category(request.getCategory()).build();
                }
                MailJob job = composer.compose(recipient, content);
                batch.add(request.getDedupeKey() != null ? job.withDedupeKey(request.getDedupeKey().apply(recipient)) : job);
            }
            return mail.enqueue(batch);
        } catch (RuntimeException e) {
            log.error("Failed to queue emails ({}): {}", request.getCategory(), e.getMessage());
            return 0;
        }
    }

    static Notification notification(String user, String type, Notice notice) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(notice.getType());
        notification.setTitle(notice.getTitle());
        notification.setMessage(notice.getMessage() == null ? "" : notice.getMessage());
        notification.setLink(notice.getLink());
        return notification;
    }

    /** Distinct, valid ObjectId strings in their original order. */
    static Set<String> ids(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(value -> value != null && ObjectId.isValid(value)).forEach(result::add);
        }
        return result;
    }
}
