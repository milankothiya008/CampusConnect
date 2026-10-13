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
 * A club's recruitment drive (models/RecruitmentDrive.js): built by the president, approved by the faculty mentor,
 * then published for applications. Any write must clear the cached clubs directory (utils/Caches.js
 * clubDirectoryCache).
 */
@Getter
@Setter
@NoArgsConstructor
@Document("recruitmentdrives")
@CompoundIndexes({
    @CompoundIndex(name = "club_1_status_1", def = "{'club': 1, 'status': 1}"),
    @CompoundIndex(name = "status_1_applicationEnd_1", def = "{'status': 1, 'applicationEnd': 1}"),
    // Venue bookings held by scheduled offline rounds.
    @CompoundIndex(
            name = "positions.rounds.venue_1_positions.rounds.startAt_1",
            def = "{'positions.rounds.venue': 1, 'positions.rounds.startAt': 1}")
})
public class RecruitmentDrive extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private String title;
    private String description;
    private List<RecruitmentPosition> positions = new ArrayList<>();
    private RecruitmentEligibility eligibility = new RecruitmentEligibility();
    private Instant applicationStart;
    private Instant applicationEnd;

    /** Set when the president closes applications before the deadline. */
    private Instant closedAt;

    private RecruitmentStatus status = RecruitmentStatus.DRAFT;
    private Instant submittedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String reviewedBy;

    private Instant reviewedAt;
    private String reviewComment;
    private Instant publishedAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private String cancellationReason;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;
}
