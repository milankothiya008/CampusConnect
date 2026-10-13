package com.campusconnect.domain.recruitment;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A round reminder already sent to the candidate (RecruitmentApplication.remindersSent; no own _id). Claimed
 * atomically per (round, kind) so it goes out once; {@code kind} is "60m" or "10m" (RecruitmentReminderService).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SentInterviewReminder {

    /** The {@link RecruitmentRound} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String round;

    private String kind;
    private Instant at;
}
