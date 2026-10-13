package com.campusconnect.feature.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.realtime.PresenceRegistry;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Live chat over STOMP (/ws) with two signed-in tabs, and the club chat following club membership. */
class ChatRealtimeIntegrationTest extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    PresenceRegistry presence;

    @Autowired
    ApplicationEventPublisher events;

    private ChatStompClient tab(TestUser user) throws InterruptedException {
        ChatStompClient tab = ChatStompClient.connect(port, user.token());
        await().atMost(WAIT).until(() -> presence.isOnline(user.id()));
        return tab;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> frame) {
        assertThat(frame).as("frame").isNotNull();
        return (Map<String, Object>) frame.get("payload");
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoSignedInUsersReceiveNewMessagesInRealTime() throws Exception {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        try (ChatStompClient ashaTab = tab(asha); ChatStompClient binaTab = tab(bina)) {
            String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");
            Response sent = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "See you at 5", "clientId", "c-live"));
            assertThat(sent.status()).isEqualTo(200);

            Map<String, Object> received = payload(binaTab.next("message:new", WAIT));
            assertThat(received.get("conversationId")).isEqualTo(chatId);
            Map<String, Object> message = (Map<String, Object>) received.get("message");
            assertThat(message.get("_id")).isEqualTo(sent.string("$.data._id"));
            assertThat(message.get("text")).isEqualTo("See you at 5");
            assertThat(message.get("clientId")).isEqualTo("c-live");
            assertThat(((Map<String, Object>) message.get("sender")).get("name")).isEqualTo("Asha");
            // Everyone gets the same copy; each tab works out "mine" from the sender.
            assertThat(message).doesNotContainKey("mine");
            Map<String, Object> header = (Map<String, Object>) received.get("conversation");
            assertThat(header).containsEntry("_id", chatId).containsEntry("type", "DIRECT").containsKeys("title", "avatar");
            assertThat((List<String>) received.get("mutedFor")).isEmpty();

            // The sender's other tabs see it too.
            assertThat(payload(ashaTab.next("message:new", WAIT)).get("conversationId")).isEqualTo(chatId);
        }
    }

    @Test
    void typingReachesEveryoneElseInTheChatAndDeliveredReachesTheSender() throws Exception {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        TestUser outsider = data.student(s -> s.name("Out"));
        try (ChatStompClient ashaTab = tab(asha); ChatStompClient binaTab = tab(bina); ChatStompClient outsiderTab = tab(outsider)) {
            String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");

            ashaTab.send("/app/typing", Map.of("conversationId", chatId, "recording", true));
            Map<String, Object> typing = payload(binaTab.next("typing", WAIT));
            assertThat(typing.get("conversationId")).isEqualTo(chatId);
            assertThat(typing.get("user")).isEqualTo(Map.of("_id", asha.id(), "name", "Asha"));
            assertThat(typing.get("recording")).isEqualTo(true);

            // Someone outside the chat can't pretend to type in it; nobody hears their own typing.
            outsiderTab.send("/app/typing", Map.of("conversationId", chatId));
            assertThat(binaTab.all("typing", Duration.ofMillis(500))).isEmpty();
            assertThat(ashaTab.all("typing", Duration.ofMillis(200))).isEmpty();

            api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Hello"));
            binaTab.send("/app/delivered", Map.of("conversationId", chatId));
            Map<String, Object> receipt = payload(ashaTab.next("receipt", WAIT));
            assertThat(receipt.get("conversationId")).isEqualTo(chatId);
            assertThat(receipt.get("userId")).isEqualTo(bina.id());
            assertThat(receipt.get("deliveredAt")).isNotNull();
            assertThat(receipt).doesNotContainKey("readAt");
        }
    }

    @Test
    void openingTheChatListMarksWaitingMessagesDeliveredToTheirSenders() throws Exception {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        TestUser chirag = data.student(s -> s.name("Chirag"));
        String fromBina = api.as(bina).post("/api/chat/direct", Map.of("userId", asha.id())).string("$.data._id");
        String fromChirag = api.as(chirag).post("/api/chat/direct", Map.of("userId", asha.id())).string("$.data._id");
        api.as(bina).post("/api/chat/conversations/" + fromBina + "/messages", Map.of("text", "Hi Asha"));
        api.as(chirag).post("/api/chat/conversations/" + fromChirag + "/messages", Map.of("text", "Hello Asha"));
        try (ChatStompClient binaTab = tab(bina); ChatStompClient chiragTab = tab(chirag)) {
            assertThat((List<?>) api.as(asha).get("/api/chat/conversations").at("$.data")).hasSize(2);
            assertThat(payload(binaTab.next("receipt", WAIT))).containsEntry("conversationId", fromBina).containsEntry("userId", asha.id()).containsKey("deliveredAt");
            assertThat(payload(chiragTab.next("receipt", WAIT))).containsEntry("conversationId", fromChirag).containsEntry("userId", asha.id());
        }
        await().atMost(WAIT).untilAsserted(() -> {
            Response detail = api.as(bina).get("/api/chat/conversations/" + fromBina);
            assertThat(detail.string("$.data.receipts[0].deliveredAt")).isNotBlank();
            assertThat((Object) detail.at("$.data.receipts[0].readAt")).isNull();
        });
    }

    @Test
    void typingInAClubChatReachesTheOtherMembers() throws Exception {
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        TestUser president = data.student(s -> s.name("Prez"));
        TestUser asha = data.student(s -> s.name("Asha"));
        Club club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, asha);
        String clubChatId = api.as(asha).get("/api/chat/conversations?filter=clubs").string("$.data[0]._id");
        try (ChatStompClient ashaTab = tab(asha); ChatStompClient presidentTab = tab(president); ChatStompClient mentorTab = tab(mentor)) {
            ashaTab.send("/app/typing", Map.of("conversationId", clubChatId));
            Map<String, Object> typing = payload(presidentTab.next("typing", WAIT));
            assertThat(typing.get("user")).isEqualTo(Map.of("_id", asha.id(), "name", "Asha"));
            assertThat(typing.get("recording")).isEqualTo(false);
            // The faculty mentor is not in the club's group chat.
            mentorTab.send("/app/typing", Map.of("conversationId", clubChatId));
            assertThat(presidentTab.all("typing", Duration.ofMillis(500))).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void electionCardsAndNoticesReachTheClubGroupLive() throws Exception {
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        TestUser president = data.student(s -> s.name("Prez"));
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        Club club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, asha);
        data.addMembership(club, bina);
        try (ChatStompClient ashaTab = tab(asha); ChatStompClient mentorTab = tab(mentor)) {
            Response created = api.as(president).post("/api/elections", Map.of("club", club.getId(), "role", "TREASURER",
                    "candidates", List.of(Map.of("user", asha.id()), Map.of("user", bina.id())), "closesAt", Instant.now().plusSeconds(3600).toString()));
            assertThat(created.status()).isEqualTo(201);
            String electionId = created.string("$.data._id");

            Map<String, Object> card = payload(ashaTab.next("message:new", WAIT));
            Map<String, Object> cardMessage = (Map<String, Object>) card.get("message");
            assertThat(cardMessage.get("type")).isEqualTo("POLL");
            assertThat(cardMessage.get("poll")).isEqualTo(electionId);
            assertThat(cardMessage.get("text")).isEqualTo("Treasurer election");
            assertThat(((Map<String, Object>) cardMessage.get("sender")).get("name")).isEqualTo("Prez");
            assertThat((Map<String, Object>) card.get("conversation")).containsEntry("type", "CLUB").containsEntry("title", "Coding Club");

            Map<String, Object> notice = payload(ashaTab.next("message:new", WAIT));
            Map<String, Object> noticeMessage = (Map<String, Object>) notice.get("message");
            assertThat(noticeMessage.get("type")).isEqualTo("SYSTEM");
            assertThat(noticeMessage.get("text")).isEqualTo("🗳️ Voting is open: Treasurer election");
            assertThat(noticeMessage.get("sender")).isNull();
            assertThat(noticeMessage.get("system")).isEqualTo(Map.of("action", "ELECTION_OPEN", "actor", "CampusConnect"));

            assertThat(payload(ashaTab.next("election:updated", WAIT))).isEqualTo(Map.of("electionId", electionId));
            // The faculty mentor follows elections on the club page, not in the members' group chat.
            assertThat(mentorTab.all("message:new", Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMembershipChangeKeepsTheClubGroupInStepWithTheClub() throws Exception {
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        TestUser president = data.student(s -> s.name("Prez"));
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        Club club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, asha);
        String clubChatId = api.as(asha).get("/api/chat/conversations?filter=clubs").string("$.data[0]._id");

        Response before = api.as(president).get("/api/chat/conversations/" + clubChatId);
        assertThat((List<String>) before.at("$.data.members[*]._id")).containsExactlyInAnyOrder(president.id(), asha.id());
        assertThat((Integer) before.at("$.data.memberCount")).isEqualTo(2);

        // Joining: the club's new member is in the group straight away (nothing is removed from anyone).
        try (ChatStompClient binaTab = tab(bina)) {
            data.addMembership(club, bina);
            events.publishEvent(new ClubMembershipChanged(club.getId(), bina.id()));
            assertThat(binaTab.all("conversation:removed", Duration.ofMillis(300))).isEmpty();
        }
        Response joined = api.as(president).get("/api/chat/conversations/" + clubChatId);
        assertThat((List<String>) joined.at("$.data.members[*]._id")).containsExactlyInAnyOrder(president.id(), asha.id(), bina.id());
        assertThat((Integer) joined.at("$.data.memberCount")).isEqualTo(3);

        // Leaving: they lose the chat at once, live, and drop out of its member list everywhere.
        try (ChatStompClient ashaTab = tab(asha)) {
            mongo.remove(Query.query(Criteria.where("club").is(new ObjectId(club.getId())).and("user").is(new ObjectId(asha.id()))), ClubMembership.class);
            events.publishEvent(new ClubMembershipChanged(club.getId(), asha.id()));
            assertThat(payload(ashaTab.next("conversation:removed", WAIT))).isEqualTo(Map.of("conversationId", clubChatId));
        }
        Response left = api.as(president).get("/api/chat/conversations/" + clubChatId);
        assertThat((List<String>) left.at("$.data.members[*]._id")).containsExactlyInAnyOrder(president.id(), bina.id());
        assertThat((Integer) left.at("$.data.memberCount")).isEqualTo(2);
        assertThat(api.as(asha).get("/api/chat/conversations/" + clubChatId).status()).isEqualTo(404);
        assertThat((List<?>) api.as(asha).get("/api/chat/conversations?filter=clubs").at("$.data")).isEmpty();
    }
}
