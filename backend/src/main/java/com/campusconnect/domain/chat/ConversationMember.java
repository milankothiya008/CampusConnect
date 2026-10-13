package com.campusconnect.domain.chat;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A chat member with their own read and notification state (Conversation.members; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class ConversationMember {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private ConversationMemberRole role = ConversationMemberRole.MEMBER;

    /** History is visible from here (when they were added, or when they cleared the chat: clearedAt). */
    private Instant joinedAt = Instant.now();

    private Instant clearedAt;
    private Instant lastReadAt;
    private Instant lastDeliveredAt;
    private Instant mutedUntil;

    @Field(targetType = FieldType.OBJECT_ID)
    private String addedBy;
}
