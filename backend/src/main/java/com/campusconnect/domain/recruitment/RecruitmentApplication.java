package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A student's application for one role of a recruitment drive (models/RecruitmentApplication.js). A student may
 * apply to several roles, each with its own application.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("recruitmentapplications")
@CompoundIndexes({
    @CompoundIndex(
            name = "drive_1_applicant_1_position_1",
            def = "{'drive': 1, 'applicant': 1, 'position': 1}",
            unique = true),
    @CompoundIndex(
            name = "drive_1_position_1_status_1_createdAt_1",
            def = "{'drive': 1, 'position': 1, 'status': 1, 'createdAt': 1}"),
    @CompoundIndex(name = "status_1_offerExpiresAt_1", def = "{'status': 1, 'offerExpiresAt': 1}"),
    @CompoundIndex(name = "applicant_1_createdAt_-1", def = "{'applicant': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "slots.startAt_1", def = "{'slots.startAt': 1}")
})
public class RecruitmentApplication extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String drive;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String applicant;

    /** The role (the drive's {@link RecruitmentPosition} _id) this application is for. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String position;

    private List<ApplicationAnswer> answers = new ArrayList<>();
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    /** Published results, one per round the candidate took part in. */
    private List<RoundResult> roundResults = new ArrayList<>();

    private PendingOutcome pendingOutcome = new PendingOutcome();
    private List<InterviewSlot> slots = new ArrayList<>();
    private List<SentInterviewReminder> remindersSent = new ArrayList<>();

    // Offer to join in this role: accept by offerExpiresAt.
    private Instant offeredAt;
    private Instant offerExpiresAt;
    private Instant respondedAt;
    private Instant decidedAt;
    private Instant withdrawnAt;

    /** Why the application ended without the student acting (e.g. they accepted another role). */
    private String closedReason;
}
