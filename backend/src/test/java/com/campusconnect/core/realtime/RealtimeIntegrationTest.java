package com.campusconnect.core.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.StompTestClient;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** STOMP over WebSocket (docs/REALTIME.md) with the in-memory broker. */
class RealtimeIntegrationTest extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    RealtimeGateway gateway;

    @Autowired
    PresenceRegistry presence;

    @Test
    void aSignedInTabReceivesEventsSentToItsUser() throws Exception {
        TestUser student = data.student();
        try (StompTestClient tab = StompTestClient.connect(port, student.token())) {
            await().atMost(WAIT).untilAsserted(() -> {
                gateway.emitToUser(student.id(), "conversation:updated", Map.of("conversationId", "c1"));
                Map<String, Object> frame = tab.next("conversation:updated", Duration.ofMillis(300));
                assertThat(frame).isNotNull();
                assertThat(frame.get("payload")).isEqualTo(Map.of("conversationId", "c1"));
            });
        }
    }

    @Test
    void connectingWithoutAValidTokenIsRefusedWithUnauthorized() {
        try (StompTestClient anonymous = StompTestClient.open(port, null)) {
            assertThat(anonymous.errorMessage(WAIT)).isEqualTo("UNAUTHORIZED");
        }
        try (StompTestClient forged = StompTestClient.open(port, "forged")) {
            assertThat(forged.errorMessage(WAIT)).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void clientsCanOnlySubscribeToTheirOwnQueueAndOnlySendToAppHandlers() {
        TestUser student = data.student();
        try (StompTestClient tab = StompTestClient.connect(port, student.token())) {
            tab.subscribe("/topic/cc.user-registry");
            assertThat(tab.errorMessage(WAIT)).isEqualTo("UNAUTHORIZED");
        }
        try (StompTestClient tab = StompTestClient.connect(port, student.token())) {
            tab.send("/queue/events-user-somebody-else", Map.of("hack", true));
            assertThat(tab.errorMessage(WAIT)).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void presenceWatchAnswersWithASnapshotAndAnnouncesWhenSomeoneLeaves() throws Exception {
        TestUser watcher = data.student();
        TestUser friend = data.student();
        try (StompTestClient watching = StompTestClient.connect(port, watcher.token())) {
            StompTestClient friendTab = StompTestClient.connect(port, friend.token());
            await().atMost(WAIT).until(() -> presence.isOnline(friend.id()));

            watching.send("/app/presence.watch", Map.of("ids", List.of(friend.id()), "requestId", "r1"));
            Map<String, Object> snapshot = watching.next("presence:snapshot", WAIT);
            assertThat(snapshot).isNotNull();
            Map<String, Object> payload = (Map<String, Object>) snapshot.get("payload");
            assertThat(payload.get("requestId")).isEqualTo("r1");
            assertThat((List<Map<String, Object>>) payload.get("states")).singleElement()
                    .satisfies(state -> {
                        assertThat(state.get("userId")).isEqualTo(friend.id());
                        assertThat(state.get("online")).isEqualTo(true);
                    });

            friendTab.close();
            Map<String, Object> left = watching.next("presence", WAIT);
            assertThat(left).isNotNull();
            Map<String, Object> state = (Map<String, Object>) left.get("payload");
            assertThat(state.get("userId")).isEqualTo(friend.id());
            assertThat(state.get("online")).isEqualTo(false);
            assertThat(state.get("lastSeenAt")).isNotNull();
        }
    }

    @Test
    void hiddenActivityStatusIsNeverRevealed() throws Exception {
        TestUser watcher = data.student();
        TestUser shy = data.student();
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("email").is(shy.email())),
                new org.springframework.data.mongodb.core.query.Update().set("showActivityStatus", false), "users");
        try (StompTestClient watching = StompTestClient.connect(port, watcher.token())) {
            watching.send("/app/presence.watch", Map.of("ids", List.of(shy.id()), "requestId", "r2"));
            Map<String, Object> snapshot = watching.next("presence:snapshot", WAIT);
            assertThat(snapshot.get("payload").toString()).contains("hidden=true").doesNotContain("online");
        }
    }

    @Test
    void theChatOnScreenIsTrackedSoItsViewersAreNotNotified() throws Exception {
        TestUser student = data.student();
        try (StompTestClient tab = StompTestClient.connect(port, student.token())) {
            tab.send("/app/viewing", Map.of("conversationId", "65aa00000000000000000001", "visible", true));
            await().atMost(WAIT).until(() -> presence.viewersOf("65aa00000000000000000001").contains(student.id()));
            tab.send("/app/viewing", Map.of("visible", false));
            await().atMost(WAIT).until(() -> presence.viewersOf("65aa00000000000000000001").isEmpty());
        }
    }
}
