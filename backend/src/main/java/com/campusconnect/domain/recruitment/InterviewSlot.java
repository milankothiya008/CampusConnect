package com.campusconnect.domain.recruitment;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A candidate's time in a round (RecruitmentApplication.slots; no own _id): an individual interview slot, or the
 * round's common time copied per candidate, so reminders work the same way for both.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSlot {

    /** The {@link RecruitmentRound} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String round;

    private Instant startAt;
    private Instant endAt;
}
