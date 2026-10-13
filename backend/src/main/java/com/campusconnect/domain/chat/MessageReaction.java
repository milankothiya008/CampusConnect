package com.campusconnect.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One person's emoji reaction to a message (Message.reactions; no own _id). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MessageReaction {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private String emoji;
}
