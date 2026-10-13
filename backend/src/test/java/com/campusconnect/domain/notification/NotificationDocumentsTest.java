package com.campusconnect.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class NotificationDocumentsTest extends MongoDocumentTest {

    @Autowired
    NotificationRepository notifications;

    @Autowired
    PushSubscriptionRepository pushSubscriptions;

    @Test
    void notificationRoundTrip() {
        Notification notification = new Notification();
        notification.setUser(newId());
        notification.setType(NotificationType.ELECTION);
        notification.setTitle("Voting is open");
        notification.setMessage("Vote for the next president");
        notification.setLink("/clubs/x/elections/y");
        notification.setReadAt(at(5));
        notifications.save(notification);

        assertSameDocument(notifications.findById(notification.getId()).orElseThrow(), notification);
        Document raw = raw("notifications");
        assertObjectIds(raw, "_id", "user");
        assertThat(raw.getString("type")).isEqualTo("ELECTION");
        assertDates(raw, "readAt", "createdAt");
        JsonNode json = toJson(notification);
        assertThat(json.get("_id").stringValue()).isEqualTo(notification.getId());
        assertThat(json.get("readAt").stringValue()).isEqualTo(at(5).toString());
        assertThat(json.get("type").stringValue()).isEqualTo("ELECTION");
    }

    @Test
    void unreadNotificationShowsReadAtNull() {
        Notification notification = new Notification();
        notification.setUser(newId());
        notification.setType(NotificationType.ANNOUNCEMENT);
        notification.setTitle("Hello");
        notifications.save(notification);

        assertAbsent(raw("notifications"), "readAt", "link");
        JsonNode json = toJson(notification);
        assertThat(json.get("readAt").isNull()).isTrue();
        assertThat(json.get("message").stringValue()).isEmpty();
    }

    private static PushSubscription subscription(String endpoint) {
        PushSubscription subscription = new PushSubscription();
        subscription.setUser(newId());
        subscription.setEndpoint(endpoint);
        subscription.setKeys(new PushSubscription.Keys("p256", "auth"));
        return subscription;
    }

    @Test
    void pushSubscriptionRoundTripAndUniqueEndpoint() {
        PushSubscription subscription = subscription("https://push.example/abc");
        subscription.setUserAgent("Chrome on Android");
        subscription.setLastSuccessAt(at(1));
        subscription.setFailures(2);
        pushSubscriptions.save(subscription);

        assertSameDocument(pushSubscriptions.findById(subscription.getId()).orElseThrow(), subscription);
        Document raw = raw("pushsubscriptions");
        assertObjectIds(raw, "_id", "user");
        assertThat(valueAt(raw, "keys.p256dh")).isEqualTo("p256");
        assertAbsent(raw, "keys._id");
        assertThat(toJson(subscription).get("keys").get("auth").stringValue()).isEqualTo("auth");
        assertThatThrownBy(() -> pushSubscriptions.save(subscription("https://push.example/abc")))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
