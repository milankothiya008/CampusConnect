package com.campusconnect.domain.event;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.FormAnswer;
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
 * A team entered in a team event (models/Team.js). The student who registers is the leader and holds the team's
 * place; the students they invite join by accepting (each gets their own registration linked to the team).
 */
@Getter
@Setter
@NoArgsConstructor
@Document("teams")
@CompoundIndexes({
    // Team names are unique within an event (ignoring case) among active teams.
    @CompoundIndex(
            name = "event_1_nameKey_1",
            def = "{'event': 1, 'nameKey': 1}",
            unique = true,
            partialFilter = "{'status': 'ACTIVE'}"),
    @CompoundIndex(name = "event_1_status_1", def = "{'event': 1, 'status': 1}"),
    @CompoundIndex(name = "members.user_1_members.status_1", def = "{'members.user': 1, 'members.status': 1}")
})
public class Team extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    private String name;

    /** Lower-cased name: team names are unique within an event, ignoring case. */
    private String nameKey;

    @Field(targetType = FieldType.OBJECT_ID)
    private String leader;

    private List<TeamMember> members = new ArrayList<>();

    /** Leader plus accepted members; kept in step atomically so a team can never go over the size limit. */
    private int size = 1;

    private TeamStatus status = TeamStatus.ACTIVE;

    /** The leader's answers to the registration form's "once per team" questions. */
    private List<FormAnswer> answers = new ArrayList<>();

    private Instant disbandedAt;
}
