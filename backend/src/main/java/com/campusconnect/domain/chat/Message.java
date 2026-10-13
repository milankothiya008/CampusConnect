package com.campusconnect.domain.chat;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One chat message (models/Message.js). Attachments keep only storage details; URLs are built on read. */
@Getter
@Setter
@NoArgsConstructor
@Document("messages")
@CompoundIndexes({
    @CompoundIndex(name = "conversation_1_createdAt_-1", def = "{'conversation': 1, 'createdAt': -1}"),
    // A resend after a dropped connection (same clientId) isn't stored twice.
    @CompoundIndex(
            name = "conversation_1_sender_1_clientId_1",
            def = "{'conversation': 1, 'sender': 1, 'clientId': 1}",
            unique = true,
            partialFilter = "{'clientId': {'$type': 'string'}}")
})
public class Message extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String conversation;

    /** Null for SYSTEM messages. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String sender;

    private MessageType type = MessageType.TEXT;
    private String text = "";
    private List<MessageAttachment> attachments = new ArrayList<>();
    private LinkPreview link;

    @Field(targetType = FieldType.OBJECT_ID)
    private String replyTo;

    /** POLL messages: the ClubElection shown as a card. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String poll;

    private boolean forwarded = false;
    private List<MessageReaction> reactions = new ArrayList<>();

    /** The sender's own id for this message (max 64 characters); null is not stored. */
    private String clientId;

    private Instant editedAt;

    /** Unsent for everyone: the text and files are removed, a placeholder stays. */
    private Instant deletedAt;

    /** Deleted for these people only. */
    @Field(targetType = FieldType.OBJECT_ID)
    private List<String> hiddenFor = new ArrayList<>();

    /** SYSTEM messages only. */
    private SystemAction system;
}
