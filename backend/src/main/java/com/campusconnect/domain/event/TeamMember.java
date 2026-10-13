package com.campusconnect.domain.event;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One entry of a team's roster (Team.members; no own _id): the leader or an invited student. */
@Getter
@Setter
@NoArgsConstructor
public class TeamMember {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private TeamMemberStatus status;
    private Instant invitedAt = Instant.now();
    private Instant respondedAt;
}
