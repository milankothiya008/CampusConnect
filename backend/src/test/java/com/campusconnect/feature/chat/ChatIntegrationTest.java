package com.campusconnect.feature.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.push.PushPayload;
import com.campusconnect.core.push.PushService;
import com.campusconnect.domain.chat.LinkPreview;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.feature.chat.link.LinkPreviewService;
import com.campusconnect.feature.chat.link.PublicAddresses;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Port of tests/integration/chat.test.js. */
class ChatIntegrationTest extends IntegrationTest {

    static final byte[] PNG = HexFormat.of().parseHex("89504e470d0a1a0a0000000d4948445200000001000000010806000000");
    static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DOCX = docx();

    private static byte[] docx() {
        byte[] bytes = new byte[44];
        bytes[0] = 0x50;
        bytes[1] = 0x4b;
        bytes[2] = 0x03;
        bytes[3] = 0x04;
        return bytes;
    }

    @Autowired
    ChatDelivery delivery;

    @Autowired
    ApplicationEventPublisher events;

    /** Development storage: upload to /api/chat/media, then attach the returned token on send. */
    Response upload(TestUser user, String conversationId, byte[] bytes, String filename, String kind) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1);
        return api.perform(MockMvcRequestBuilders.multipart("/api/chat/media?conversation=" + conversationId + "&kind=" + kind + "&ext=" + ext)
                .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                .header("Authorization", "Bearer " + user.token()));
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    @Nested
    class Chat {

        TestUser asha;
        TestUser bina;
        TestUser chirag;
        TestUser dev;
        TestUser president;
        TestUser mentor;
        TestUser admin;
        Club club;

        @BeforeEach
        void people() {
            asha = data.student(s -> s.name("Asha"));
            bina = data.student(s -> s.name("Bina"));
            chirag = data.student(s -> s.name("Chirag"));
            dev = data.student(s -> s.name("Dev"));
            president = data.student(s -> s.name("Prez"));
            mentor = data.faculty("CE", "Dr Mentor");
            admin = data.admin();
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, asha);
            data.addMembership(club, bina);
        }

        String send(TestUser user, String chatId, Object payload) {
            return api.as(user).post("/api/chat/conversations/" + chatId + "/messages", payload).string("$.data._id");
        }

        List<String> texts(TestUser user, String chatId) {
            return api.as(user).get("/api/chat/conversations/" + chatId + "/messages").at("$.data.items[*].text");
        }

        @Test
        void oneToOneAnyoneCanMessageAnyoneWithUnreadCountsReadReceiptsEditUnsendReactionsAndReplies() {
            Response opened = api.as(asha).post("/api/chat/direct", Map.of("userId", mentor.id()));
            assertThat(opened.status()).isEqualTo(200);
            String chatId = opened.string("$.data._id");
            assertThat(opened.string("$.data.title")).isEqualTo("Dr Mentor");

            // Nothing in the mentor's list until there's a message.
            assertThat((List<?>) api.as(mentor).get("/api/chat/conversations").at("$.data[?(@.type == 'DIRECT')]")).isEmpty();

            Response first = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Good morning sir", "clientId", "c-1"));
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.message()).isEqualTo("Sent");
            // A resend with the same clientId isn't stored twice.
            api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Good morning sir", "clientId", "c-1"));
            assertThat(mongo.count(Query.query(Criteria.where("conversation").is(new ObjectId(chatId))), Message.class)).isEqualTo(1);

            Response list = api.as(mentor).get("/api/chat/conversations");
            assertThat(list.string("$.data[0]._id")).isEqualTo(chatId);
            assertThat(list.string("$.data[0].title")).isEqualTo("Asha");
            assertThat((Integer) list.at("$.data[0].unread")).isEqualTo(1);
            assertThat(list.string("$.data[0].lastMessage.preview")).isEqualTo("Good morning sir");
            assertThat((Boolean) list.at("$.data[0].lastMessage.mine")).isFalse();
            Response unread = api.as(mentor).get("/api/chat/unread");
            assertThat((Integer) unread.at("$.data.chats")).isEqualTo(1);
            assertThat((Integer) unread.at("$.data.messages")).isEqualTo(1);

            String firstId = first.string("$.data._id");
            Response reply = api.as(mentor).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Morning!", "replyTo", firstId));
            assertThat(reply.string("$.data.replyTo.text")).isEqualTo("Good morning sir");
            api.as(mentor).post("/api/chat/conversations/" + chatId + "/read");
            Response detail = api.as(asha).get("/api/chat/conversations/" + chatId);
            assertThat(detail.string("$.data.receipts[0].readAt")).isNotBlank();

            Response edited = api.as(asha).patch("/api/chat/messages/" + firstId, Map.of("text", "Good morning, sir"));
            assertThat(edited.string("$.data.text")).isEqualTo("Good morning, sir");
            assertThat(edited.string("$.data.editedAt")).isNotBlank();
            assertThat(api.as(mentor).patch("/api/chat/messages/" + firstId, Map.of("text", "hacked")).status()).isEqualTo(403);

            Response reacted = api.as(mentor).put("/api/chat/messages/" + firstId + "/reaction", Map.of("emoji", "❤️"));
            assertThat((List<?>) reacted.at("$.data.reactions")).hasSize(1);
            assertThat(reacted.string("$.data.reactions[0].emoji")).isEqualTo("❤️");
            assertThat((Integer) reacted.at("$.data.reactions[0].count")).isEqualTo(1);
            assertThat((Boolean) reacted.at("$.data.reactions[0].mine")).isTrue();
            assertThat(api.as(mentor).put("/api/chat/messages/" + firstId + "/reaction", Map.of("emoji", "not an emoji")).status()).isEqualTo(400);

            assertThat(api.as(mentor).delete("/api/chat/messages/" + firstId + "?for=everyone").status()).isEqualTo(403);
            api.as(asha).delete("/api/chat/messages/" + firstId + "?for=everyone");
            Response page = api.as(mentor).get("/api/chat/conversations/" + chatId + "/messages");
            assertThat((Boolean) page.at("$.data.items[0].deleted")).isTrue();
            assertThat(page.string("$.data.items[0].text")).isEmpty();

            // Delete for me hides it only for me.
            api.as(asha).delete("/api/chat/messages/" + reply.string("$.data._id") + "?for=me");
            assertThat(texts(asha, chatId)).doesNotContain("Morning!");
            assertThat(texts(mentor, chatId)).contains("Morning!");

            // Strangers can't read it.
            assertThat(api.as(chirag).get("/api/chat/conversations/" + chatId + "/messages").status()).isEqualTo(404);
        }

        @Test
        void blockingStopsOneToOneMessagesBothWaysUntilUnblocked() {
            String chatId = api.as(chirag).post("/api/chat/direct", Map.of("userId", dev.id())).string("$.data._id");
            send(chirag, chatId, Map.of("text", "hey"));
            api.as(dev).put("/api/chat/blocks/" + chirag.id(), Map.of("blocked", true));

            Response refused = api.as(chirag).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "hello?"));
            assertThat(refused.status()).isEqualTo(403);
            assertThat(api.as(dev).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "x")).message()).contains("Unblock");
            assertThat((List<String>) api.as(dev).get("/api/chat/blocks").at("$.data[*].name")).containsExactly("Chirag");

            api.as(dev).put("/api/chat/blocks/" + chirag.id(), Map.of("blocked", false));
            assertThat(api.as(chirag).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "hello again")).status()).isEqualTo(200);
        }

        @Test
        void groupsCreateAddAndRemovePeopleAnnounceOnlyAdminsAndLeaving() {
            Response created = api.as(asha).post("/api/chat/groups", Map.of("name", "Hackathon squad", "members", List.of(bina.id(), chirag.id())));
            assertThat(created.status()).isEqualTo(200);
            String groupId = created.string("$.data._id");
            assertThat((List<String>) created.at("$.data.members[*].name")).containsExactly("Asha", "Bina", "Chirag");
            assertThat((List<String>) created.at("$.data.members[*].role")).containsExactly("ADMIN", "MEMBER", "MEMBER");

            assertThat(api.as(bina).post("/api/chat/conversations/" + groupId + "/members", Map.of("userIds", List.of(dev.id()))).status()).isEqualTo(403);
            api.as(asha).post("/api/chat/conversations/" + groupId + "/members", Map.of("userIds", List.of(dev.id())));
            api.as(asha).delete("/api/chat/conversations/" + groupId + "/members/" + chirag.id());
            assertThat(api.as(chirag).get("/api/chat/conversations/" + groupId).status()).isEqualTo(404);

            api.as(asha).patch("/api/chat/conversations/" + groupId, Map.of("announceOnly", true, "name", "Squad"));
            assertThat(api.as(bina).post("/api/chat/conversations/" + groupId + "/messages", Map.of("text", "can I talk?")).status()).isEqualTo(403);
            assertThat(api.as(asha).post("/api/chat/conversations/" + groupId + "/messages", Map.of("text", "Announcement")).status()).isEqualTo(200);

            // System messages record what happened.
            List<String> texts = texts(dev, groupId);
            assertThat(texts).contains("Asha removed Chirag", "Asha renamed the group to \"Squad\"", "Announcement");
            // Dev was added later: the creation message isn't in their history.
            assertThat(texts).noneMatch(text -> text.contains("created the group"));

            // The only admin leaving hands admin to the longest-standing member.
            api.as(asha).post("/api/chat/conversations/" + groupId + "/leave");
            Response after = api.as(bina).get("/api/chat/conversations/" + groupId);
            assertThat((List<String>) after.at("$.data.members[?(@.name == 'Bina')].role")).containsExactly("ADMIN");
        }

        @Test
        void clubGroupEveryApprovedMemberIsInItTheMentorAndOutsidersAreNotAndLeavingTheClubRemovesAccess() {
            Response ashaList = api.as(asha).get("/api/chat/conversations?filter=clubs");
            assertThat((List<?>) ashaList.at("$.data")).hasSize(1);
            String clubChatId = ashaList.string("$.data[0]._id");
            assertThat(ashaList.string("$.data[0].type")).isEqualTo("CLUB");
            assertThat(ashaList.string("$.data[0].title")).isEqualTo("Coding Club");
            assertThat((Integer) ashaList.at("$.data[0].memberCount")).isEqualTo(3);

            assertThat((List<?>) api.as(mentor).get("/api/chat/conversations?filter=clubs").at("$.data")).isEmpty();
            assertThat(api.as(mentor).get("/api/chat/conversations/" + clubChatId).status()).isEqualTo(404);
            assertThat(api.as(dev).get("/api/chat/conversations/" + clubChatId + "/messages").status()).isEqualTo(404);

            send(bina, clubChatId, Map.of("text", "Meeting at 5?"));
            Response presList = api.as(president).get("/api/chat/conversations");
            assertThat((List<Integer>) presList.at("$.data[?(@.type == 'CLUB')].unread")).containsExactly(1);

            // Only holders of "Manage club chat" (the president) can switch on announce-only or pin.
            assertThat(api.as(asha).patch("/api/chat/conversations/" + clubChatId, Map.of("announceOnly", true)).status()).isEqualTo(403);
            api.as(president).patch("/api/chat/conversations/" + clubChatId, Map.of("announceOnly", true));
            assertThat(api.as(asha).post("/api/chat/conversations/" + clubChatId + "/messages", Map.of("text", "hi")).status()).isEqualTo(403);
            String notice = send(president, clubChatId, Map.of("text", "Notice: meeting moved"));
            Response pinned = api.as(president).put("/api/chat/conversations/" + clubChatId + "/pins/" + notice, Map.of("pinned", true));
            assertThat((List<String>) pinned.at("$.data.pinned[*].text")).containsExactly("Notice: meeting moved");
            api.as(president).patch("/api/chat/conversations/" + clubChatId, Map.of("announceOnly", false));

            // Granting the authority to a role lets its holders manage the chat too.
            List<ClubRole> roles = new ArrayList<>(ClubRoles.defaultRoles());
            roles.stream().filter(role -> role.getKey().equals("MEMBER")).findFirst().orElseThrow().setPermissions(new ArrayList<>(List.of("MANAGE_CHAT")));
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("roles", roles), Club.class);
            assertThat((Boolean) api.as(asha).get("/api/chat/conversations/" + clubChatId).at("$.data.isAdmin")).isTrue();

            // Leaving the club (the clubs feature removes the membership and announces the change).
            mongo.remove(Query.query(Criteria.where("club").is(new ObjectId(club.getId())).and("user").is(new ObjectId(bina.id()))), ClubMembership.class);
            events.publishEvent(new ClubMembershipChanged(club.getId(), bina.id()));
            assertThat(api.as(bina).get("/api/chat/conversations/" + clubChatId + "/messages").status()).isEqualTo(404);
            assertThat((List<?>) api.as(bina).get("/api/chat/conversations?filter=clubs").at("$.data")).isEmpty();
        }

        @Test
        void photosDocumentsAndVoiceNotesAreUploadedForOneChatAndSentAsAttachmentsForwardingCopiesThem() {
            String chatId = api.as(asha).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");

            Response photo = upload(asha, chatId, PNG, "photo.png", "IMAGE");
            assertThat(photo.status()).isEqualTo(200);
            Response pdf = upload(asha, chatId, PDF, "notes.pdf", "DOCUMENT");
            Response docx = upload(asha, chatId, DOCX, "report.docx", "DOCUMENT");
            assertThat(List.of(pdf.status(), docx.status())).containsExactly(200, 200);
            // A file pretending to be a Word document is refused.
            assertThat(upload(asha, chatId, "just text".getBytes(StandardCharsets.UTF_8), "fake.docx", "DOCUMENT").status()).isEqualTo(400);
            // Someone outside the chat can't upload into it.
            assertThat(upload(chirag, chatId, PNG, "x.png", "IMAGE").status()).isEqualTo(404);

            Map<String, Object> photoRef = photo.at("$.data");
            Response sentPhoto = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("text", "Poster draft", "attachments", List.of(photoRef)));
            assertThat(sentPhoto.string("$.data.type")).isEqualTo("MEDIA");
            assertThat(sentPhoto.string("$.data.attachments[0].kind")).isEqualTo("IMAGE");
            assertThat(sentPhoto.string("$.data.attachments[0].url")).isNotBlank();
            Map<String, Object> pdfRef = new LinkedHashMap<>(pdf.at("$.data"));
            pdfRef.put("name", "notes.pdf");
            Map<String, Object> docxRef = new LinkedHashMap<>(docx.at("$.data"));
            docxRef.put("name", "report.docx");
            Response sentDocs = api.as(asha).post("/api/chat/conversations/" + chatId + "/messages", Map.of("attachments", List.of(pdfRef, docxRef)));
            assertThat(sentDocs.string("$.data.type")).isEqualTo("FILE");
            assertThat((List<String>) sentDocs.at("$.data.attachments[*].name")).containsExactly("notes.pdf", "report.docx");
            assertThat((List<String>) sentDocs.at("$.data.attachments[*].format")).containsExactly("pdf", "docx");

            // An upload issued for one chat can't be attached in another.
            String other = api.as(asha).post("/api/chat/direct", Map.of("userId", dev.id())).string("$.data._id");
            assertThat(api.as(asha).post("/api/chat/conversations/" + other + "/messages", Map.of("attachments", List.of(photoRef))).status()).isEqualTo(400);

            Response forwarded = api.as(asha).post("/api/chat/messages/" + sentPhoto.string("$.data._id") + "/forward", Map.of("conversationIds", List.of(other)));
            assertThat((List<String>) forwarded.at("$.data.forwardedTo")).containsExactly(other);
            Response copy = api.as(dev).get("/api/chat/conversations/" + other + "/messages");
            assertThat((Boolean) copy.at("$.data.items[-1].forwarded")).isTrue();
            assertThat(copy.string("$.data.items[-1].text")).isEqualTo("Poster draft");
            assertThat(copy.string("$.data.items[-1].attachments[0].kind")).isEqualTo("IMAGE");

            assertThat((List<?>) api.as(bina).get("/api/chat/conversations/" + chatId + "/shared?kind=docs").at("$.data")).hasSize(1);
        }

        @Test
        void newMessagesNotifyEveryoneInTheChatExceptTheSenderPeopleWhoMutedItAndPeopleWhoTurnedNotificationsOff() {
            PushService push = mock(PushService.class);
            Object original = ReflectionTestUtils.getField(delivery, "push");
            ReflectionTestUtils.setField(delivery, "push", push);
            try {
                String groupId = api.as(asha).post("/api/chat/groups", Map.of("name", "Notify test", "members", List.of(bina.id(), chirag.id(), dev.id()))).string("$.data._id");

                // Instagram's mute choices; the bell state comes back on the chat.
                assertThat((Boolean) api.as(bina).put("/api/chat/conversations/" + groupId + "/mute", Map.of("duration", "15m")).at("$.data.muted")).isTrue();
                assertThat(api.as(bina).put("/api/chat/conversations/" + groupId + "/mute", Map.of("duration", "2d")).status()).isEqualTo(400);
                assertThat((Boolean) api.as(bina).get("/api/chat/conversations/" + groupId).at("$.data.muted")).isTrue();
                api.as(dev).put("/api/chat/settings", Map.of("chatNotifications", false));

                send(asha, groupId, Map.of("text", "Standup at 10"));
                ArgumentCaptor<Collection<String>> targets = targetsCaptor();
                ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
                verify(push, times(1)).pushToUsers(targets.capture(), payload.capture());
                assertThat(targets.getValue()).containsExactly(chirag.id());
                assertThat(payload.getValue().title()).isEqualTo("Notify test");
                assertThat(payload.getValue().body()).isEqualTo("Asha: Standup at 10");
                assertThat(payload.getValue().url()).isEqualTo("/messages/" + groupId);
                assertThat(payload.getValue().kind()).isEqualTo("chat");
                assertThat(payload.getValue().conversationId()).isEqualTo(groupId);

                // Unmuting brings notifications back; one-to-one chats are titled with the sender's name.
                Map<String, Object> unmute = new LinkedHashMap<>();
                unmute.put("duration", null);
                api.as(bina).put("/api/chat/conversations/" + groupId + "/mute", unmute);
                clearInvocations(push);
                String direct = api.as(chirag).post("/api/chat/direct", Map.of("userId", bina.id())).string("$.data._id");
                send(chirag, direct, Map.of("text", "Hi Bina"));
                verify(push, times(1)).pushToUsers(targets.capture(), payload.capture());
                assertThat(targets.getValue()).containsExactly(bina.id());
                assertThat(payload.getValue().title()).isEqualTo("Chirag");
                assertThat(payload.getValue().body()).isEqualTo("Hi Bina");

                api.as(dev).put("/api/chat/settings", Map.of("chatNotifications", true));
            } finally {
                ReflectionTestUtils.setField(delivery, "push", original);
            }
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<Collection<String>> targetsCaptor() {
            return ArgumentCaptor.forClass((Class<Collection<String>>) (Class<?>) Collection.class);
        }

        @Test
        void reportsReachTheUniversityAdminWithTheMessageText() {
            String chatId = api.as(dev).post("/api/chat/direct", Map.of("userId", asha.id())).string("$.data._id");
            String rude = send(dev, chatId, Map.of("text", "something rude"));
            assertThat(api.as(dev).post("/api/chat/messages/" + rude + "/report", Map.of("reason", "x")).status()).isEqualTo(400);
            assertThat(api.as(asha).post("/api/chat/messages/" + rude + "/report", Map.of("reason", "Harassment")).status()).isEqualTo(200);

            assertThat(api.as(asha).get("/api/chat/reports").status()).isEqualTo(403);
            Response reports = api.as(admin).get("/api/chat/reports");
            assertThat(reports.string("$.data[0].text")).isEqualTo("something rude");
            assertThat(reports.string("$.data[0].reason")).isEqualTo("Harassment");
            assertThat(reports.string("$.data[0].status")).isEqualTo("OPEN");
            assertThat(reports.string("$.data[0].sender.name")).isEqualTo("Dev");
            api.as(admin).put("/api/chat/reports/" + reports.string("$.data[0]._id"), Map.of("note", "Warned"));
            assertThat((List<?>) api.as(admin).get("/api/chat/reports").at("$.data")).isEmpty();
        }

        @Test
        void peopleSearchFindsStudentsAndFacultyNeverTheAdminOrYourselfActivityStatusCanBeHidden() {
            assertThat((List<String>) api.as(asha).get("/api/chat/people?q=Dr").at("$.data[*].name")).contains("Dr Mentor");
            List<String> everyone = api.as(asha).get("/api/chat/people?q=a").at("$.data[*].name");
            assertThat(everyone).doesNotContain("Asha");
            assertThat(everyone).noneMatch(name -> name.toLowerCase().contains("admin"));

            assertThat((Map<String, Object>) api.as(asha).put("/api/chat/settings", Map.of("showActivityStatus", false)).at("$.data"))
                    .isEqualTo(Map.of("showActivityStatus", false, "chatNotifications", true));
        }
    }

    @Nested
    class LinkPreviews {

        @Test
        void onlyPublicAddressesAreFetched() {
            for (String ip : List.of("127.0.0.1", "10.1.2.3", "192.168.0.10", "172.20.1.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1", "::ffff:127.0.0.1", "0.0.0.0")) {
                assertThat(PublicAddresses.isPublicAddress(ip)).as(ip).isFalse();
            }
            for (String ip : List.of("8.8.8.8", "142.250.183.14", "2606:4700:4700::1111")) {
                assertThat(PublicAddresses.isPublicAddress(ip)).as(ip).isTrue();
            }
        }

        @Test
        void readsOpenGraphTagsAndFindsTheFirstLink() {
            String html = "<html><head><meta property=\"og:title\" content=\"CampusConnect &amp; you\"><meta name=\"description\" content=\"Clubs and events\">"
                    + "<meta property=\"og:image\" content=\"/cover.png\"></head></html>";
            LinkPreview preview = LinkPreviewService.parse(html, "https://example.com/page");
            assertThat(preview.getUrl()).isEqualTo("https://example.com/page");
            assertThat(preview.getTitle()).isEqualTo("CampusConnect & you");
            assertThat(preview.getDescription()).isEqualTo("Clubs and events");
            assertThat(preview.getImage()).isEqualTo("https://example.com/cover.png");
            assertThat(preview.getSite()).isEqualTo("example.com");
            assertThat(LinkPreviewService.firstLink("see https://ddu.ac.in/events, thanks")).isEqualTo("https://ddu.ac.in/events");
        }
    }
}
