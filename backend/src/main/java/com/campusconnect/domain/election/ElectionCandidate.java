package com.campusconnect.domain.election;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A candidate picked by the organiser (ClubElection.candidates; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class ElectionCandidate {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    /** A short pitch the organiser can add ("Ran the tech fest last year"). */
    private String statement = "";

    /** Hidden until voting closes; only ever incremented atomically. */
    private int votes = 0;
}
