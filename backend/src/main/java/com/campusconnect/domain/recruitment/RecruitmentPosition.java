package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.SubDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A role being recruited for (RecruitmentDrive.positions), with its own form, rounds and results. */
@Getter
@Setter
@NoArgsConstructor
public class RecruitmentPosition extends SubDocument {

    /** Key of one of the club's roles (Club.roles): what a student who accepts becomes. */
    private String role;

    /** The role's name when the drive was created. */
    private String title;

    /** Null = no fixed number of openings. */
    private Integer openings;

    private String description = "";
    private PositionForm form = new PositionForm();
    private List<RecruitmentRound> rounds = new ArrayList<>();

    /** Set when the president makes the final selection for this role (offers, reserves, not selected). */
    private Instant finalizedAt;

    /** Days a student has to accept an offer, 1–14. */
    private int offerDays = 3;
}
