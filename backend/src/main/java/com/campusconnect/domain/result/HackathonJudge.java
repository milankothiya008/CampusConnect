package com.campusconnect.domain.result;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A judge on a hackathon's panel (Hackathon.judges; no own _id): faculty, or a student not taking part. */
@Getter
@Setter
@NoArgsConstructor
public class HackathonJudge {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    @Field(targetType = FieldType.OBJECT_ID)
    private String addedBy;

    private Instant addedAt = Instant.now();
}
