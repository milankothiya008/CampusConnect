package com.campusconnect.domain.recruitment;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * The president's decision for the current round (RecruitmentApplication.pendingOutcome), not shown to the student
 * until published. All null when there is none.
 */
@Getter
@Setter
@NoArgsConstructor
public class PendingOutcome {

    /** The {@link RecruitmentRound} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String round;

    private RoundOutcome outcome;
    private String note;
}
