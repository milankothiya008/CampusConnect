package com.campusconnect.domain.club;

import com.campusconnect.domain.audit.AuditAction;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One step of a club request's review trail (ClubCreationRequest.history; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class ClubRequestHistoryEntry {

    /** The audit action of the step: CLUB_REQUEST_SUBMITTED, CLUB_REQUEST_VERIFIED, … */
    private AuditAction action;

    /** Null for the first submission. */
    private ClubRequestStatus fromStatus;

    private ClubRequestStatus toStatus;

    @Field(targetType = FieldType.OBJECT_ID)
    private String actor;

    private String reason;
    private Instant at = Instant.now();
}
