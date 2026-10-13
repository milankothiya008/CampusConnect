package com.campusconnect.domain.result;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One judge's scores for a hackathon entry (HackathonEntry.scores; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class JudgeScore {

    @Field(targetType = FieldType.OBJECT_ID)
    private String judge;

    private List<ScoreMark> marks = new ArrayList<>();

    /** Sum of the marks, kept with the scores for sorting. */
    private double total = 0;

    private String comment = "";
    private Instant scoredAt = Instant.now();
}
