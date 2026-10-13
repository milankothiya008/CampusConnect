package com.campusconnect.domain.club;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A student's membership of a club (models/ClubMembership.js). {@code role} is a key of the club's roles. */
@Getter
@Setter
@NoArgsConstructor
@Document("clubmemberships")
@CompoundIndexes({
    @CompoundIndex(name = "club_1_user_1", def = "{'club': 1, 'user': 1}", unique = true),
    @CompoundIndex(name = "user_1_status_1", def = "{'user': 1, 'status': 1}"),
    @CompoundIndex(name = "club_1_role_1_status_1", def = "{'club': 1, 'role': 1, 'status': 1}"),
    // A club has one president and at most one vice-president.
    @CompoundIndex(
            name = "one_president_one_vice_president",
            def = "{'club': 1, 'role': 1}",
            unique = true,
            partialFilter = "{'role': {'$in': ['PRESIDENT', 'VICE_PRESIDENT']}, 'status': 'APPROVED'}")
})
public class ClubMembership extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private String role = "MEMBER";
    private MembershipStatus status = MembershipStatus.PENDING;
    private String requestMessage;

    @Field(targetType = FieldType.OBJECT_ID)
    private String decidedBy;

    private Instant decidedAt;
    private String decisionReason;
    private Instant joinedAt;
}
