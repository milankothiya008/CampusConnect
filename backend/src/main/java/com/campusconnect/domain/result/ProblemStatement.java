package com.campusconnect.domain.result;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A hackathon problem statement (Hackathon.problemStatements), hidden from participants until revealAt. */
@Getter
@Setter
@NoArgsConstructor
public class ProblemStatement extends SubDocument {

    private String title;
    private String description;

    /** Optional theme, e.g. "Health", "FinTech". */
    private String track = "";

    /** How many teams may pick it (null = any number). */
    private Integer maxTeams;
}
