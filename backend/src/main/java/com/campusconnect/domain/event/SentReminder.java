package com.campusconnect.domain.event;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A reminder already sent for an event (Event.remindersSent; no own _id). Automatic reminders are claimed here so
 * they go out once; manual ones are rate-limited from it. {@code kind}: REG_CLOSING, START_24H, START_1H, FEEDBACK
 * or MANUAL_*. {@code by} is null for automatic reminders.
 */
@Getter
@Setter
@NoArgsConstructor
public class SentReminder {

    private String kind;
    private Instant at = Instant.now();

    @Field(targetType = FieldType.OBJECT_ID)
    private String by;

    private int recipients = 0;
}
