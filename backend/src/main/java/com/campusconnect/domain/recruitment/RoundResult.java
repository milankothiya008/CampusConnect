package com.campusconnect.domain.recruitment;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A published result of one round the candidate took part in (RecruitmentApplication.roundResults; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class RoundResult {

    /** The {@link RecruitmentRound} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String round;

    private RoundOutcome outcome;
    private String note;
    private Instant publishedAt;
}
