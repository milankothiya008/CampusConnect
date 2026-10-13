package com.campusconnect.domain.club;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A student's request to found a club (models/ClubCreationRequest.js): the proposed faculty mentor verifies it, the
 * admin approves it and the {@link Club} is created. Every step is kept in {@code history}.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("clubcreationrequests")
@CompoundIndex(name = "status_1_createdAt_-1", def = "{'status': 1, 'createdAt': -1}")
public class ClubCreationRequest extends BaseDocument {

    private String name;
    private String description;
    private String purpose;
    private String proposedActivities;
    private String reason;

    /** Either open to every department, or limited to departmentCodes (see DepartmentScope). */
    private boolean allDepartments = false;
    private List<String> departmentCodes = new ArrayList<>();

    /** One of core.util.Categories.CLUB_CATEGORIES. */
    private String category;

    @Indexed(name = "requester_1")
    @Field(targetType = FieldType.OBJECT_ID)
    private String requester;

    @Indexed(name = "foundingMembers_1")
    @Field(targetType = FieldType.OBJECT_ID)
    private List<String> foundingMembers = new ArrayList<>();

    @Field(targetType = FieldType.OBJECT_ID)
    private String proposedMentor;

    private ClubRequestStatus status = ClubRequestStatus.PENDING_FACULTY_REVIEW;

    @Field(targetType = FieldType.OBJECT_ID)
    private String verifiedBy;

    private Instant verifiedAt;
    private String reviewComment;

    @Field(targetType = FieldType.OBJECT_ID)
    private String decidedBy;

    private Instant decidedAt;
    private String rejectionReason;

    /** The club created on approval. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private List<ClubRequestHistoryEntry> history = new ArrayList<>();
}
