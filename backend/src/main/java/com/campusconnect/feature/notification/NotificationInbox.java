package com.campusconnect.feature.notification;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.tokens.UnsubscribeTokens;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** A person's notifications, e-mail settings and unsubscribe links (the read side of NotificationService.js). */
@Service
public class NotificationInbox {

    /** The kinds shown in the Activity bubble (like Instagram's likes / comments / follows counts). */
    private static final Map<String, Set<String>> KIND_OF = Map.of(
            "recruitment", Set.of("RECRUITMENT_REVIEW", "RECRUITMENT_UPDATE", "RECRUITMENT_OPEN", "APPLICATION_UPDATE", "INTERVIEW_SCHEDULED", "INTERVIEW_REMINDER", "RECRUITMENT_OFFER"),
            "clubs", Set.of("CLUB_REQUEST_UPDATE", "CLUB_UPDATE", "MEMBERSHIP_REQUEST", "MEMBERSHIP_APPROVED", "MEMBERSHIP_REJECTED", "CLUB_ROLE_CHANGED", "ANNOUNCEMENT", "NEW_CLUB", "GALLERY_SUBMITTED", "GALLERY_APPROVED", "GALLERY_REJECTED"));

    private final MongoOperations mongo;
    private final Refs refs;
    private final UnsubscribeTokens unsubscribeTokens;
    private final SubscriptionService subscriptions;

    public NotificationInbox(MongoOperations mongo, Refs refs, UnsubscribeTokens unsubscribeTokens, SubscriptionService subscriptions) {
        this.mongo = mongo;
        this.refs = refs;
        this.unsubscribeTokens = unsubscribeTokens;
        this.subscriptions = subscriptions;
    }

    public record Listing(List<Notification> items, Map<String, Object> meta) {
    }

    public Listing list(AuthUser user, String page, String limit, String unread) {
        Pagination pagination = Pagination.of(page, limit, 20);
        ObjectId owner = new ObjectId(user.id());
        Criteria filter = Criteria.where("user").is(owner);
        if ("true".equals(unread)) {
            filter = filter.and("readAt").is(null);
        }
        Query query = Query.query(filter).with(Sort.by(Sort.Direction.DESC, "createdAt")).skip(pagination.skip()).limit(pagination.limit());
        List<Notification> items = mongo.find(query, Notification.class);
        long total = mongo.count(Query.query(filter), Notification.class);
        var pageMeta = pagination.meta(total);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unread", unreadCount(user));
        meta.put("total", pageMeta.total());
        meta.put("page", pageMeta.page());
        meta.put("limit", pageMeta.limit());
        meta.put("totalPages", pageMeta.totalPages());
        return new Listing(items, meta);
    }

    /** Notifications the person has not read yet (NotificationService.unreadCount). */
    public long unreadCount(AuthUser user) {
        return mongo.count(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("readAt").is(null)), Notification.class);
    }

    /** Unread notifications: the total, plus how many are about events, clubs and recruitment. */
    public Map<String, Object> unreadSummary(AuthUser user) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("user").is(new ObjectId(user.id())).and("readAt").is(null)),
                Aggregation.group("type").count().as("count"));
        Map<String, Long> kinds = new LinkedHashMap<>();
        kinds.put("events", 0L);
        kinds.put("clubs", 0L);
        kinds.put("recruitment", 0L);
        long count = 0;
        for (Document row : mongo.aggregate(aggregation, "notifications", Document.class)) {
            long rowCount = ((Number) row.get("count")).longValue();
            count += rowCount;
            String type = String.valueOf(row.get("_id"));
            String kind = KIND_OF.entrySet().stream().filter(entry -> entry.getValue().contains(type)).map(Map.Entry::getKey).findFirst().orElse("events");
            kinds.merge(kind, rowCount, Long::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", count);
        summary.put("kinds", kinds);
        return summary;
    }

    public Notification markRead(AuthUser user, String id) {
        Notification notification = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(id)).and("user").is(new ObjectId(user.id()))),
                new Update().set("readAt", Instant.now()).set("updatedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true),
                Notification.class);
        if (notification == null) {
            throw AppException.notFound("Notification not found");
        }
        return notification;
    }

    public Map<String, Object> markAllRead(AuthUser user) {
        long updated = mongo.updateMulti(
                Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("readAt").is(null)),
                new Update().set("readAt", Instant.now()).set("updatedAt", Instant.now()),
                Notification.class).getModifiedCount();
        return Map.of("updated", updated);
    }

    // ---------------------------------------------------------------- e-mail preferences

    public Map<String, Object> getEmailPreferences(AuthUser actor) {
        Map<String, Object> user = refs.user(actor.id(), "emailPreferences");
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = user == null ? null : (Map<String, Object>) user.get("emailPreferences");
        Map<String, Object> preferences = new LinkedHashMap<>();
        EmailCategory.OPTIONAL.forEach(category -> preferences.put(category.key(), stored == null || !Boolean.FALSE.equals(stored.get(category.key()))));
        List<Map<String, String>> categories = EmailCategory.OPTIONAL.stream()
                .map(category -> Map.of("key", category.key(), "label", category.label(), "description", category.description()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferences", preferences);
        result.put("categories", categories);
        return result;
    }

    public Map<String, Object> updateEmailPreferences(AuthUser actor, Map<String, Boolean> changes) {
        Update update = new Update();
        boolean any = false;
        for (EmailCategory category : EmailCategory.OPTIONAL) {
            Boolean value = changes == null ? null : changes.get(category.key());
            if (value != null) {
                update.set("emailPreferences." + category.key(), value);
                any = true;
            }
        }
        if (!any) {
            throw AppException.badRequest("Nothing to update", ErrorCode.VALIDATION_ERROR);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(actor.id()))), update.set("updatedAt", Instant.now()), "users");
        return getEmailPreferences(actor);
    }

    // ---------------------------------------------------------------- unsubscribe links

    /** The unsubscribe page is public, so it only hints at which address the link belongs to. */
    static String maskEmail(String email) {
        return String.valueOf(email).replaceAll("^(.{2})[^@]*(@.*)$", "$1***$2");
    }

    private UnsubscribeTokens.Unsubscribe readToken(String token) {
        UnsubscribeTokens.Unsubscribe parsed = unsubscribeTokens.read(token);
        boolean valid = parsed != null && ("club".equals(parsed.scope())
                ? parsed.key().matches("(?i)^[a-f0-9]{24}$")
                : EmailCategory.OPTIONAL.stream().anyMatch(category -> category.key().equals(parsed.key())));
        if (!valid || !ObjectId.isValid(parsed.userId())) {
            throw AppException.badRequest("This unsubscribe link is invalid or has been changed", ErrorCode.TOKEN_INVALID);
        }
        return parsed;
    }

    public Map<String, Object> describeUnsubscribe(String token) {
        UnsubscribeTokens.Unsubscribe link = readToken(token);
        Map<String, Object> user = refs.user(link.userId(), "email");
        if (user == null) {
            throw AppException.badRequest("This unsubscribe link is invalid", ErrorCode.TOKEN_INVALID);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", link.scope());
        result.put("email", maskEmail(String.valueOf(user.get("email"))));
        if ("club".equals(link.scope())) {
            Map<String, Object> club = refs.club(link.key(), "name");
            result.put("label", "Emails from " + (club != null && club.get("name") != null ? club.get("name") : "this club"));
            result.put("clubId", link.key());
            return result;
        }
        EmailCategory category = EmailCategory.fromKey(link.key());
        result.put("label", category.label());
        result.put("description", category.description());
        result.put("category", link.key());
        return result;
    }

    /** Works without signing in, from the e-mail link or the mail app's one-click unsubscribe button. */
    public Map<String, Object> unsubscribe(String token) {
        UnsubscribeTokens.Unsubscribe link = readToken(token);
        Map<String, Object> summary = describeUnsubscribe(token);
        if ("club".equals(link.scope())) {
            subscriptions.turnOffForUser(link.userId(), link.key());
        } else {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(link.userId()))),
                    new Update().set("emailPreferences." + link.key(), false).set("updatedAt", Instant.now()), "users");
        }
        Map<String, Object> result = new LinkedHashMap<>(summary);
        result.put("unsubscribed", true);
        return result;
    }

    static Map<NotificationType, Long> emptyCounts() {
        return new EnumMap<>(NotificationType.class);
    }
}
