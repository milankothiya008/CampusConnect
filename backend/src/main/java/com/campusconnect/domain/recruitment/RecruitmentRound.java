package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.TimestampedSubDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A selection round of one role (RecruitmentPosition.rounds). Screening rounds have no meeting; online rounds have a
 * meeting link, offline rounds a venue. Timing is one common slot for everyone or an individual slot per candidate
 * (kept on the application); startAt / endAt always span the whole round, so the venue booking covers every slot.
 * Has its own createdAt/updatedAt.
 */
@Getter
@Setter
@NoArgsConstructor
public class RecruitmentRound extends TimestampedSubDocument {

    private String name;
    private RoundMode mode;
    private RoundTiming timing;
    private Instant startAt;
    private Instant endAt;

    /** SLOTS timing: minutes per candidate, 5–240. */
    private Integer slotMinutes;

    @Field(targetType = FieldType.OBJECT_ID)
    private String venue;

    private String meetingLink;
    private String instructions = "";
    private RoundStatus status = RoundStatus.DRAFT;
    private Instant scheduledAt;
    private Instant resultsPublishedAt;
}
