package com.campusconnect.domain.chat;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** The latest message of a chat, for the chat list (Conversation.lastMessage). */
@Getter
@Setter
@NoArgsConstructor
public class LastMessage {

    @Field(targetType = FieldType.OBJECT_ID)
    private String message;

    @Field(targetType = FieldType.OBJECT_ID)
    private String sender;

    private String preview = "";

    /** "Asha added Bina": shown without "You:" in the chat list. */
    private boolean system = false;

    private Instant at;
}
