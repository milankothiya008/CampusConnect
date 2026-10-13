package com.campusconnect.domain.election;

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
 * An anonymous vote among a club's members for one of its roles (models/ClubElection.js), e.g. a new president when
 * the old one leaves or the academic year ends. The organiser picks the candidates; the result is advisory (the
 * president still appoints the role). Votes are counted on the candidates themselves and never stored with the
 * voter: {@link ElectionBallot} only records <em>that</em> someone voted, so nobody can see who chose whom.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("clubelections")
@CompoundIndexes({
    @CompoundIndex(name = "club_1_createdAt_-1", def = "{'club': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "status_1_opensAt_1", def = "{'status': 1, 'opensAt': 1}"),
    @CompoundIndex(name = "status_1_closesAt_1", def = "{'status': 1, 'closesAt': 1}")
})
public class ClubElection extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    /** The club role being decided (its key) and its name when the election was created. */
    private String role;

    private String roleName;
    private String title;
    private String description = "";
    private List<ElectionCandidate> candidates = new ArrayList<>();
    private Instant opensAt;
    private Instant closesAt;
    private ElectionStatus status = ElectionStatus.SCHEDULED;

    /** How many members could vote (fixed when voting opens) and how many did. */
    private int eligibleCount = 0;

    private int votesCast = 0;

    /** A second vote among candidates who tied: the election it settles. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String runoffOf;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    private Instant closedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String closedBy;

    private Instant cancelledAt;
    private String cancelReason = "";
}
