package com.campusconnect.domain.result;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A judging criterion of a hackathon (Hackathon.criteria), scored from 0 to maxScore (a whole number, 1–100). */
@Getter
@Setter
@NoArgsConstructor
public class JudgingCriterion extends SubDocument {

    private String name;
    private int maxScore;
}
