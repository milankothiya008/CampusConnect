package com.campusconnect.feature.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * The JSON the React app reads, key by key, in the original's order (ChatService serializeMessages /
 * conversationView / getConversation and the small results), so the reused frontend keeps working unchanged.
 */
class ChatContractIntegrationTest extends IntegrationTest {

    static final List<String> USER = List.of("_id", "name", "avatar", "accountType", "departmentCode", "batchCode");
    static final List<String> ROW = List.of("_id", "type", "title", "avatar", "other", "club", "memberCount", "lastMessage", "lastMessageAt", "unread", "muted",
            "mutedUntil", "announceOnly", "isAdmin", "createdAt");
    static final List<String> DETAIL_EXTRA = List.of("description", "members", "pinned", "receipts", "block", "presence", "canSend", "sendBlockedReason", "canEditInfo",
            "canManageMembers", "canPin");
    static final List<String> MESSAGE = List.of("_id", "conversation", "clientId", "type", "sender", "mine", "text", "attachments", "link", "replyTo", "forwarded",
            "reactions", "editedAt", "deleted", "system", "poll", "createdAt");

    static List<String> keys(Response response, String path) {
        Map<String, Object> object = response.at(path);
        return List.copyOf(object.keySet());
    }

    static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    @Test
    void conversationsMessagesAndResultsHaveTheOriginalShape() {
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        TestUser president = data.student(s -> s.name("Prez"));
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        Club club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, asha);

        Response direct = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id()));
        String chatId = direct.string("$.data._id");
        assertThat(keys(direct, "$.data")).containsExactlyElementsOf(concat(ROW, DETAIL_EXTRA));
        assertThat(keys(direct, "$.data.other")).containsExactlyElementsOf(USER);
        assertThat(keys(direct, "$.data.members[0]")).containsExactlyElementsOf(concat(USER, List.of("role")));
        assertThat(keys(direct, "$.data.receipts[0]")).containsExactly("userId", "readAt", "deliveredAt");
        assertThat(keys(direct, "$.data.block")).containsExactly("iBlocked", "blockedMe", "inactive");
        assertThat(keys(direct, "$.data.presence")).containsExactly("userId", "online", "lastSeenAt");
        assertThat((Object) direct.at("$.data.club")).isNull();
        assertThat((Object) direct.at("$.data.sendBlockedReason")).isNull();

        Response first = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Hello"));
        Response reply = api.as(bina).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Hi", "replyTo", first.string("$.data._id")));
        assertThat(keys(reply, "$.data")).containsExactlyElementsOf(MESSAGE);
        assertThat(keys(reply, "$.data.sender")).containsExactlyElementsOf(USER);
        assertThat(keys(reply, "$.data.replyTo")).containsExactly("_id", "sender", "text", "thumb");
        assertThat((Object) reply.at("$.data.link")).isNull();
        assertThat((Object) reply.at("$.data.system")).isNull();
        assertThat((Object) reply.at("$.data.poll")).isNull();
        assertThat((Object) reply.at("$.data.editedAt")).isNull();
        Response reacted = api.as(bina).put("/api/chat/messages/" + first.string("$.data._id") + "/reaction", Map.of("emoji", "👍"));
        assertThat(keys(reacted, "$.data.reactions[0]")).containsExactly("emoji", "users", "count", "mine");

        // The club chat was created by this very list, so it is the newest row; the one-to-one chat comes second.
        Response list = api.as(asha).get("/api/chat/conversations");
        assertThat((List<String>) list.at("$.data[*].type")).containsExactly("CLUB", "DIRECT");
        assertThat(keys(list, "$.data[1]")).containsExactlyElementsOf(ROW);
        assertThat(keys(list, "$.data[1].lastMessage")).containsExactly("preview", "at", "mine", "system", "senderId");
        assertThat((Object) list.at("$.data[0].lastMessage")).isNull();
        Response clubRow = api.as(asha).get("/api/chat/conversations?filter=clubs");
        assertThat(clubRow.string("$.data[0].club")).isEqualTo(club.getId());
        Response clubDetail = api.as(asha).get("/api/chat/conversations/" + clubRow.string("$.data[0]._id"));
        assertThat(keys(clubDetail, "$.data.members[0]")).containsExactlyElementsOf(concat(USER, List.of("role", "clubRole")));
        assertThat((List<?>) clubDetail.at("$.data.receipts")).isEmpty();
        assertThat((Object) clubDetail.at("$.data.block")).isNull();
        assertThat((Object) clubDetail.at("$.data.presence")).isNull();

        assertThat(keys(api.as(bina).get("/api/chat/unread"), "$.data")).containsExactly("chats", "messages", "counts", "cap", "mutedIds");
        assertThat(keys(api.as(bina).get("/api/chat/conversations/" + chatId + "/messages"), "$.data")).containsExactly("items", "hasMore");
        assertThat(keys(api.as(bina).post("/api/chat/conversations/" + chatId + "/read"), "$.data")).containsExactly("readAt", "unread", "visibleFrom");
        assertThat(keys(api.as(bina).put("/api/chat/conversations/" + chatId + "/mute", Map.of("duration", "1h")), "$.data")).containsExactly("mutedUntil", "muted");
        assertThat(keys(api.as(bina).delete("/api/chat/messages/" + reply.string("$.data._id") + "?for=me"), "$.data")).containsExactly("deleted");
        // Clearing hides everything so far, so it comes last.
        assertThat(keys(api.as(bina).post("/api/chat/conversations/" + chatId + "/clear"), "$.data")).containsExactly("clearedAt");
        assertThat(keys(api.as(bina).get("/api/chat/settings"), "$.data")).containsExactly("showActivityStatus", "chatNotifications");
        assertThat(keys(api.as(bina).put("/api/chat/blocks/" + asha.id(), Map.of("blocked", false)), "$.data")).containsExactly("blocked");
        assertThat(keys(api.as(asha).get("/api/chat/people?q=Bina"), "$.data[0]")).containsExactlyElementsOf(USER);
        assertThat(keys(api.as(asha).post("/api/chat/conversations/" + chatId + "/uploads", Map.of("kind", "IMAGE")), "$.data"))
                .containsExactly("provider", "kind", "uploadUrl", "fields", "maxBytes", "maxVideoSeconds", "expiresIn");
    }

    @Test
    void reportsHaveTheOriginalShape() {
        TestUser admin = data.admin();
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser dev = data.student(s -> s.name("Dev"));
        String chatId = api.as(dev).post("/api/chat/direct", Map.of("userId", asha.id())).string("$.data._id");
        String messageId = api.as(dev).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "rude")).string("$.data._id");
        assertThat(keys(api.as(asha).post("/api/chat/messages/" + messageId + "/report", Map.of()), "$.data")).containsExactly("reported");

        List<String> report = List.of("_id", "message", "conversation", "reporter", "sender", "reason", "text", "attachmentCount", "status", "resolvedBy", "resolvedAt",
                "note", "createdAt", "updatedAt");
        Response reports = api.as(admin).get("/api/chat/reports");
        assertThat(keys(reports, "$.data[0]")).containsExactlyElementsOf(report);
        assertThat(keys(reports, "$.data[0].reporter")).containsExactlyElementsOf(USER);
        assertThat(keys(reports, "$.data[0].sender")).containsExactlyElementsOf(concat(USER, List.of("email")));
        Response resolved = api.as(admin).put("/api/chat/reports/" + reports.string("$.data[0]._id"), Map.of("note", "Warned"));
        assertThat(keys(resolved, "$.data")).containsExactlyInAnyOrderElementsOf(report);
        assertThat(resolved.string("$.data.status")).isEqualTo("RESOLVED");
        assertThat(resolved.string("$.data.resolvedBy")).isEqualTo(admin.id());
    }

    @Test
    void validationAndAccessErrorsMatchTheOriginalMessages() {
        TestUser asha = data.student(s -> s.name("Asha"));
        TestUser bina = data.student(s -> s.name("Bina"));
        assertThat(api.as(asha).post("/api/chat/direct", Map.of()).message()).isEqualTo("Choose someone");
        assertThat(api.as(asha).post("/api/chat/direct", Map.of("userId", asha.id())).message()).isEqualTo("You can't message yourself");
        assertThat(api.as(asha).post("/api/chat/groups", Map.of("name", " ", "members", List.of(bina.id()))).message()).isEqualTo("Name the group");
        assertThat(api.as(asha).post("/api/chat/groups", Map.of("name", "Squad", "members", List.of())).message()).isEqualTo("Add people to the group");
        assertThat(api.as(asha).post("/api/chat/groups", Map.of("name", "Squad", "members", List.of(asha.id()))).message()).isEqualTo("Add at least one person");
        assertThat(api.as(asha).get("/api/chat/conversations/nope").message()).isEqualTo("Invalid id");
        Response missing = api.as(asha).get("/api/chat/conversations/" + new ObjectId().toHexString());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.message()).isEqualTo("Chat not found");
        assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(api.as(asha).get("/api/chat/conversations?filter=bogus").message()).isEqualTo("Invalid value");

        String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");
        assertThat(api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "x".repeat(4001))).message())
                .isEqualTo("Messages can be up to 4000 characters");
        assertThat(api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "   ")).message()).isEqualTo("Write a message or add a file");
        assertThat(api.as(asha).post("/api/chat/conversations/" + chatId + "/uploads", Map.of("kind", "EXE")).message()).isEqualTo("Unsupported file");
        Response leave = api.as(asha).post("/api/chat/conversations/" + chatId + "/leave");
        assertThat(leave.status()).isEqualTo(409);
        assertThat(leave.errorCode()).isEqualTo("INVALID_STATE");
        assertThat(leave.message()).isEqualTo("You can't leave a one-to-one chat — delete it instead");
        assertThat(api.as(asha).patch("/api/chat/conversations/" + chatId, Map.of("name", "x")).message()).isEqualTo("One-to-one chats have no settings");
        assertThat(api.as(asha).patch("/api/chat/conversations/" + chatId, Map.of("name", 5, "announceOnly", "maybe")).message()).isEqualTo("Invalid value. Invalid value");
        assertThat(api.get("/api/chat/conversations").status()).isEqualTo(401);
    }
}
