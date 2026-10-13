package com.campusconnect.domain.election;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * "This member has voted in this election", and nothing else (models/ElectionBallot.js). The choice itself is only
 * added to the candidate's count (ClubElection.candidates.votes), so a ballot can't be traced back to a vote; and
 * the counts stay hidden until voting closes, so watching them change can't give a vote away either. The Mongoose
 * schema has no timestamps and no version key, so this is not a BaseDocument: just _id, election and voter.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("electionballots")
@CompoundIndex(name = "election_1_voter_1", def = "{'election': 1, 'voter': 1}", unique = true)
public class ElectionBallot {

    @Id
    @JsonProperty("_id")
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String election;

    @Field(targetType = FieldType.OBJECT_ID)
    private String voter;
}
