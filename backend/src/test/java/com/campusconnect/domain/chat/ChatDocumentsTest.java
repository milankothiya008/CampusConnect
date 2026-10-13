package com.campusconnect.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.common.StoredMedia;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class ChatDocumentsTest extends MongoDocumentTest {

    @Autowired
    ConversationRepository conversations;

    @Autowired
    MessageRepository messages;

    @Autowired
    ChatReportRepository reports;

    // ------------------------------------------------------------------ Conversation

    private static ConversationMember member(String user, ConversationMemberRole role) {
        ConversationMember member = new ConversationMember();
        member.setUser(user);
        member.setRole(role);
        member.setJoinedAt(at(-100));
        return member;
    }

    private static Conversation conversation(ConversationType type) {
        Conversation conversation = new Conversation();
        conversation.setType(type);
        return conversation;
    }

    @Test
    void groupRoundTrip() {
        Conversation group = conversation(ConversationType.GROUP);
        ConversationMember admin = member(newId(), ConversationMemberRole.ADMIN);
        admin.setClearedAt(at(-50));
        admin.setLastReadAt(at(-1));
        admin.setLastDeliveredAt(at(-1));
        admin.setMutedUntil(at(1000));
        ConversationMember added = member(newId(), ConversationMemberRole.MEMBER);
        added.setAddedBy(admin.getUser());
        group.setMembers(List.of(admin, added));
        group.setName("Fest volunteers");
        group.setDescription("Logistics");
        StoredMedia avatar = new StoredMedia();
        avatar.setKind(MediaKind.IMAGE);
        avatar.setProvider("cloudinary");
        avatar.setKey("cc/chat/avatar");
        avatar.setVersion(3L);
        avatar.setFormat("webp");
        group.setAvatar(avatar);
        group.setCreatedBy(admin.getUser());
        group.setAnnounceOnly(true);
        group.setPinned(List.of(newId()));
        group.getLastMessage().setMessage(newId());
        group.getLastMessage().setSender(admin.getUser());
        group.getLastMessage().setPreview("Asha added Bina");
        group.getLastMessage().setSystem(true);
        group.getLastMessage().setAt(at(-1));
        group.setLastMessageAt(at(-1));
        conversations.save(group);

        assertSameDocument(conversations.findById(group.getId()).orElseThrow(), group);
        Document raw = raw("conversations");
        assertObjectIds(raw, "_id", "members.0.user", "members.1.user", "members.1.addedBy", "createdBy",
                "pinned.0", "lastMessage.message", "lastMessage.sender");
        // Groups have neither a direct key nor a club, so the partial unique indexes skip them.
        assertAbsent(raw, "directKey", "club", "members.0._id", "avatar._id", "lastMessage._id", "members.0.addedBy");
        assertThat(raw.getString("type")).isEqualTo("GROUP");
        assertThat(valueAt(raw, "members.0.role")).isEqualTo("ADMIN");

        JsonNode json = toJson(group);
        assertThat(json.get("_id").stringValue()).isEqualTo(group.getId());
        assertThat(json.get("avatar").get("format").stringValue()).isEqualTo("webp");
        assertThat(json.get("avatar").has("width")).isFalse(); // the avatar shape has five fields only
        assertThat(json.get("lastMessage").get("system").booleanValue()).isTrue();
        assertThat(json.get("members").get(0).get("mutedUntil").stringValue()).isEqualTo(at(1000).toString());
        assertThat(json.get("directKey").isNull()).isTrue();
    }

    @Test
    void groupsDoNotCollideOnTheirMissingKeys() {
        conversations.save(conversation(ConversationType.GROUP));
        conversations.save(conversation(ConversationType.GROUP));

        assertThat(conversations.count()).isEqualTo(2);
    }

    @Test
    void aPairHasOneDirectChat() {
        Conversation direct = conversation(ConversationType.DIRECT);
        direct.setDirectKey("a:b");
        conversations.save(direct);

        Conversation again = conversation(ConversationType.DIRECT);
        again.setDirectKey("a:b");
        assertThatThrownBy(() -> conversations.save(again)).isInstanceOf(DuplicateKeyException.class);
        assertThat(conversations.findByDirectKey("a:b")).isPresent();
    }

    @Test
    void aClubHasOneClubChat() {
        String club = newId();
        Conversation chat = conversation(ConversationType.CLUB);
        chat.setClub(club);
        conversations.save(chat);

        assertObjectIds(raw("conversations"), "club");
        Conversation again = conversation(ConversationType.CLUB);
        again.setClub(club);
        assertThatThrownBy(() -> conversations.save(again)).isInstanceOf(DuplicateKeyException.class);
        assertThat(conversations.findByClub(club)).map(Conversation::getId).contains(chat.getId());
    }

    // ------------------------------------------------------------------ Message

    private static Message message(String conversation, String sender, String clientId) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setText("Hi");
        message.setClientId(clientId);
        return message;
    }

    @Test
    void messageRoundTrip() {
        Message message = message(newId(), newId(), "c-1");
        message.setType(MessageType.MEDIA);
        MessageAttachment voice = new MessageAttachment();
        voice.setKind(MediaKind.AUDIO);
        voice.setProvider("local");
        voice.setKey("chat/voice.webm");
        voice.setFormat("webm");
        voice.setDuration(4.2);
        voice.setBytes(52_000L);
        MessageAttachment doc = new MessageAttachment();
        doc.setKind(MediaKind.DOCUMENT);
        doc.setProvider("cloudinary");
        doc.setKey("cc/chat/doc");
        doc.setVersion(9L);
        doc.setName("minutes.pdf");
        message.setAttachments(List.of(voice, doc));
        LinkPreview link = new LinkPreview();
        link.setUrl("https://example.org");
        link.setTitle("Example");
        link.setDescription("An example");
        link.setImage("https://example.org/og.png");
        link.setSite("example.org");
        message.setLink(link);
        message.setReplyTo(newId());
        message.setPoll(newId());
        message.setForwarded(true);
        message.setReactions(List.of(new MessageReaction(newId(), "🔥")));
        message.setEditedAt(at(1));
        message.setDeletedAt(at(2));
        message.setHiddenFor(List.of(newId()));
        message.setSystem(new SystemAction("ADDED", "Asha", "Bina"));
        messages.save(message);

        assertSameDocument(messages.findById(message.getId()).orElseThrow(), message);
        Document raw = raw("messages");
        assertObjectIds(raw, "_id", "conversation", "sender", "replyTo", "poll", "reactions.0.user", "hiddenFor.0");
        assertAbsent(raw, "attachments.0._id", "link._id", "reactions.0._id", "system._id", "attachments.0.version");
        assertThat(valueAt(raw, "attachments.1.name")).isEqualTo("minutes.pdf");
        assertThat(valueAt(raw, "system.actor")).isEqualTo("Asha");

        JsonNode json = toJson(message);
        assertThat(json.get("type").stringValue()).isEqualTo("MEDIA");
        assertThat(json.get("attachments").get(0).get("name").stringValue()).isEmpty();
        assertThat(json.get("attachments").get(0).get("kind").stringValue()).isEqualTo("AUDIO");
        assertThat(json.get("reactions").get(0).get("emoji").stringValue()).isEqualTo("🔥");
        assertThat(json.get("hiddenFor").get(0).stringValue()).isEqualTo(message.getHiddenFor().get(0));
    }

    @Test
    void aResentMessageIsNotStoredTwice() {
        String conversation = newId();
        String sender = newId();
        messages.save(message(conversation, sender, "c-42"));

        assertThatThrownBy(() -> messages.save(message(conversation, sender, "c-42")))
                .isInstanceOf(DuplicateKeyException.class);
        // Messages without a clientId (system messages, older clients) never collide.
        messages.save(message(conversation, sender, null));
        messages.save(message(conversation, sender, null));
        assertThat(messages.count()).isEqualTo(3);
        assertThat(new Message().getType()).isEqualTo(MessageType.TEXT);
    }

    // ------------------------------------------------------------------ ChatReport

    @Test
    void reportRoundTripAndOneReportPerPerson() {
        ChatReport report = new ChatReport();
        report.setMessage(newId());
        report.setConversation(newId());
        report.setReporter(newId());
        report.setSender(newId());
        report.setReason("Spam");
        report.setText("Buy now");
        report.setAttachmentCount(1);
        report.setStatus(ChatReportStatus.RESOLVED);
        report.setResolvedBy(newId());
        report.setResolvedAt(at(10));
        report.setNote("Warned the sender");
        reports.save(report);

        assertSameDocument(reports.findById(report.getId()).orElseThrow(), report);
        assertObjectIds(raw("chatreports"), "_id", "message", "conversation", "reporter", "sender", "resolvedBy");
        assertThat(toJson(report).get("status").stringValue()).isEqualTo("RESOLVED");
        assertThat(reports.existsByMessageAndReporter(report.getMessage(), report.getReporter())).isTrue();

        ChatReport again = new ChatReport();
        again.setMessage(report.getMessage());
        again.setConversation(report.getConversation());
        again.setReporter(report.getReporter());
        assertThatThrownBy(() -> reports.save(again)).isInstanceOf(DuplicateKeyException.class);
        assertThat(again.getStatus()).isEqualTo(ChatReportStatus.OPEN);
    }
}
