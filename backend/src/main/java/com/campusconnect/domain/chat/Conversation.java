package com.campusconnect.domain.chat;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.StoredMedia;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A chat (models/Conversation.js): one-to-one (DIRECT), a group someone created (GROUP), or a club's own group
 * (CLUB). Members are embedded with their own read and notification state. For a CLUB chat, who belongs is decided
 * by the club's approved memberships (the faculty mentor is not a member); entries here are only kept for people
 * who have opened it, to remember what they've read.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("conversations")
@CompoundIndex(name = "members.user_1_lastMessageAt_-1", def = "{'members.user': 1, 'lastMessageAt': -1}")
public class Conversation extends BaseDocument {

    private ConversationType type;
    private List<ConversationMember> members = new ArrayList<>();

    /**
     * DIRECT: the two user ids, sorted and joined, so a pair only ever has one chat. Null (not stored) for groups,
     * so the partial unique index ignores them.
     */
    @Indexed(name = "directKey_1", unique = true, partialFilter = "{'directKey': {'$type': 'string'}}")
    private String directKey;

    /** CLUB chats: the club (one chat per club). Null (not stored) for other chats. */
    @Indexed(name = "club_1", unique = true, partialFilter = "{'club': {'$type': 'objectId'}}")
    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private String name = "";
    private String description = "";

    /** Group photo (an IMAGE in media storage). */
    private StoredMedia avatar;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    /** Only admins may send (announcements). */
    private boolean announceOnly = false;

    /** Pinned messages. */
    @Field(targetType = FieldType.OBJECT_ID)
    private List<String> pinned = new ArrayList<>();

    private LastMessage lastMessage = new LastMessage();
    private Instant lastMessageAt;
}
