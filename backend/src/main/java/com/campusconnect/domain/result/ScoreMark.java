package com.campusconnect.domain.result;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A judge's mark on one criterion (JudgeScore.marks; no own _id), rounded to one decimal. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScoreMark {

    /** The {@link JudgingCriterion} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String criterion;

    private double score;
}
