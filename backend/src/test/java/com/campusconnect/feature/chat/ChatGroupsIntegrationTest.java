package com.campusconnect.feature.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Group administration, pins, clearing and photos — each change is one atomic update. */
class ChatGroupsIntegrationTest extends IntegrationTest {

    TestUser asha;
    TestUser bina;
    TestUser chirag;
    String groupId;

    @BeforeEach
    void group() {
        asha = data.student(s -> s.name("Asha"));
        bina = data.student(s -> s.name("Bina"));
        chirag = data.student(s -> s.name("Chirag"));
        groupId = api.as(asha).post("/api/chat/groups", Map.of("name", "Fest crew", "members", List.of(bina.id(), chirag.id()))).string("$.data._id");
    }

    String send(TestUser user, String text) {
        return api.as(user).post("/api/chat/conversations/" + groupId + "/messages", Map.of("text", text)).string("$.data._id");
    }

    Response setAdmin(TestUser actor, TestUser target, boolean admin) {
        return api.as(actor).put("/api/chat/conversations/" + groupId + "/members/" + target.id() + "/admin", Map.of("admin", admin));
    }

    @Test
    void adminsCanBePromotedAndStepDownButAGroupAlwaysKeepsOne() {
        Response alone = setAdmin(asha, asha, false);
        assertThat(alone.status()).isEqualTo(400);
        assertThat(alone.message()).isEqualTo("Make someone else an admin first");
        assertThat(setAdmin(bina, chirag, true).status()).isEqualTo(403);

        Response promoted = setAdmin(asha, bina, true);
        assertThat((List<String>) promoted.at("$.data.members[?(@.role == 'ADMIN')].name")).containsExactly("Asha", "Bina");
        assertThat((Boolean) api.as(bina).get("/api/chat/conversations/" + groupId).at("$.data.canManageMembers")).isTrue();

        Response steppedDown = setAdmin(asha, asha, false);
        assertThat(steppedDown.status()).isEqualTo(200);
        assertThat((List<String>) steppedDown.at("$.data.members[?(@.role == 'ADMIN')].name")).containsExactly("Bina");
        assertThat((Boolean) steppedDown.at("$.data.isAdmin")).isFalse();
        assertThat(setAdmin(bina, bina, false).message()).isEqualTo("Make someone else an admin first");

        Response stranger = api.as(bina).put("/api/chat/conversations/" + groupId + "/members/" + data.student().id() + "/admin", Map.of("admin", true));
        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.message()).isEqualTo("They're not in this group");
    }

    @Test
    void threePinsAtMostANewPinReplacesTheOldestAndPinsCanBeRemoved() {
        List<String> ids = new ArrayList<>();
        for (String text : List.of("one", "two", "three", "four")) {
            String id = send(asha, text);
            ids.add(id);
            api.as(asha).put("/api/chat/conversations/" + groupId + "/pins/" + id, Map.of("pinned", true));
        }
        Response detail = api.as(bina).get("/api/chat/conversations/" + groupId);
        assertThat((List<String>) detail.at("$.data.pinned[*].text")).containsExactlyInAnyOrder("two", "three", "four");
        assertThat((Boolean) detail.at("$.data.canPin")).isFalse();
        assertThat(api.as(bina).put("/api/chat/conversations/" + groupId + "/pins/" + ids.get(0), Map.of("pinned", true)).status()).isEqualTo(403);

        Response unpinned = api.as(asha).put("/api/chat/conversations/" + groupId + "/pins/" + ids.get(2), Map.of("pinned", false));
        assertThat((List<String>) unpinned.at("$.data.pinned[*].text")).containsExactlyInAnyOrder("two", "four");
        // Unsending a pinned message unpins it.
        api.as(asha).delete("/api/chat/messages/" + ids.get(3) + "?for=everyone");
        assertThat((List<String>) api.as(asha).get("/api/chat/conversations/" + groupId).at("$.data.pinned[*].text")).containsExactly("two");
        assertThat((List<String>) api.as(bina).get("/api/chat/conversations/" + groupId + "/messages").at("$.data.items[*].text")).contains("Asha pinned a message");
    }

    @Test
    void clearingAChatHidesItsHistoryForThatPersonOnly() {
        send(asha, "before");
        Response cleared = api.as(bina).post("/api/chat/conversations/" + groupId + "/clear");
        assertThat(cleared.string("$.data.clearedAt")).isNotBlank();
        send(asha, "after");
        assertThat((List<String>) api.as(bina).get("/api/chat/conversations/" + groupId + "/messages").at("$.data.items[*].text")).containsExactly("after");
        assertThat((List<String>) api.as(chirag).get("/api/chat/conversations/" + groupId + "/messages").at("$.data.items[*].text")).contains("before", "after");
        assertThat((Integer) api.as(bina).get("/api/chat/unread").at("$.data.messages")).isEqualTo(1);
    }

    @Test
    void theGroupPhotoIsAnUploadedImageAndCanBeRemoved() {
        byte[] png = ChatIntegrationTest.PNG;
        Response upload = api.perform(MockMvcRequestBuilders.multipart("/api/chat/media?conversation=" + groupId + "&kind=IMAGE&ext=png")
                .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                .header("Authorization", "Bearer " + asha.token()));
        assertThat(upload.status()).isEqualTo(200);
        Map<String, Object> photo = upload.at("$.data");

        assertThat(api.as(bina).patch("/api/chat/conversations/" + groupId, Map.of("avatar", photo)).status()).isEqualTo(403);
        Response withPhoto = api.as(asha).patch("/api/chat/conversations/" + groupId, Map.of("avatar", photo, "description", "  Fest planning  "));
        assertThat(withPhoto.string("$.data.avatar")).contains("/uploads/chat/" + groupId + "/");
        assertThat(withPhoto.string("$.data.description")).isEqualTo("Fest planning");
        assertThat((List<String>) api.as(bina).get("/api/chat/conversations/" + groupId + "/messages").at("$.data.items[*].text")).contains("Asha changed the group photo");

        Map<String, Object> remove = new LinkedHashMap<>();
        remove.put("avatar", null);
        assertThat((Object) api.as(asha).patch("/api/chat/conversations/" + groupId, remove).at("$.data.avatar")).isNull();

        Map<String, Object> notAnImage = Map.of("avatar", Map.of("provider", "local", "token", "forged.token"));
        Response refused = api.as(asha).patch("/api/chat/conversations/" + groupId, notAnImage);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("UPLOAD_ERROR");
        assertThat(api.as(asha).patch("/api/chat/conversations/" + groupId, Map.of("name", "   ")).message()).isEqualTo("Name the group");
    }
}
