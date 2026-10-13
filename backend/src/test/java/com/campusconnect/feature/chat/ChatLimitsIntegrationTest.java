package com.campusconnect.feature.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The limits that keep chat fast and fair, and storage that stays compatible with the original database. */
class ChatLimitsIntegrationTest extends IntegrationTest {

    @Test
    void unreadCountsStopAtTheCapSoALongUnreadChatStaysCheap() {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");
        List<Message> backlog = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            Message message = new Message();
            message.setConversation(chatId);
            message.setSender(asha.id());
            message.setText("Message " + i);
            backlog.add(message);
        }
        mongo.insert(backlog, Message.class);
        api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Are you there?"));

        Response unread = api.as(bina).get("/api/chat/unread");
        assertThat((Integer) unread.at("$.data.messages")).isEqualTo(ConversationService.UNREAD_CAP);
        assertThat((Integer) unread.at("$.data.cap")).isEqualTo(100);
        assertThat((Integer) unread.at("$.data.counts['" + chatId + "']")).isEqualTo(100);
        assertThat((Integer) api.as(bina).get("/api/chat/conversations").at("$.data[0].unread")).isEqualTo(100);
        // The sender's own messages never count for them.
        assertThat((Integer) api.as(asha).get("/api/chat/unread").at("$.data.messages")).isZero();
    }

    @Test
    void sendingIsLimitedTo25MessagesInQuickSuccession() {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");
        for (int i = 0; i < SendRateLimit.MAX_MESSAGES; i++) {
            assertThat(api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "spam " + i)).status()).isEqualTo(200);
        }
        Response refused = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "one more"));
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(refused.message()).isEqualTo("You're sending messages too quickly — wait a few seconds");
        // Someone else is not affected.
        assertThat(api.as(bina).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "calm down")).status()).isEqualTo(200);
    }

    @Test
    void referencesAreStoredAsObjectIdsLikeTheOriginalDatabase() {
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        TestUser president = data.student(s -> s.name("Prez"));
        TestUser asha = data.student(s -> s.name("Asha"));
        Club club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, asha);
        String clubChatId = api.as(asha).get("/api/chat/conversations?filter=clubs").string("$.data[0]._id");
        String messageId = api.as(asha).post("/api/chat/conversations/" + clubChatId + "/messages", Map.of("text", "Hello club")).string("$.data._id");
        api.as(president).put("/api/chat/messages/" + messageId + "/reaction", Map.of("emoji", "🔥"));
        api.as(president).put("/api/chat/conversations/" + clubChatId + "/pins/" + messageId, Map.of("pinned", true));

        Document chat = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(clubChatId))), Document.class, "conversations");
        assertThat(chat.get("club")).isInstanceOf(ObjectId.class);
        List<Document> members = chat.getList("members", Document.class);
        assertThat(members).isNotEmpty().allSatisfy(member -> {
            assertThat(member.get("user")).isInstanceOf(ObjectId.class);
            assertThat(member.get("role")).isEqualTo("MEMBER");
        });
        Document last = chat.get("lastMessage", Document.class);
        assertThat(last.get("message")).isInstanceOf(ObjectId.class);
        assertThat(last.get("sender")).isInstanceOf(ObjectId.class);
        assertThat(chat.getList("pinned", Object.class)).singleElement().isInstanceOf(ObjectId.class);

        Document message = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(messageId))), Document.class, "messages");
        assertThat(message.get("conversation")).isInstanceOf(ObjectId.class);
        assertThat(message.get("sender")).isInstanceOf(ObjectId.class);
        assertThat(message.getList("reactions", Document.class)).singleElement().satisfies(reaction -> assertThat(reaction.get("user")).isInstanceOf(ObjectId.class));
        // The sender's read marker moved with their message (one write).
        Document sender = members.stream().filter(member -> member.get("user").equals(new ObjectId(asha.id()))).findFirst().orElseThrow();
        assertThat(sender.getDate("lastReadAt")).isNotNull();
    }
}
