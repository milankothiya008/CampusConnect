package com.campusconnect.domain.recruitment;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Who may apply (RecruitmentDrive.eligibility). Departments come from the club's own scope; the president can narrow
 * the batches (empty = every batch).
 */
@Getter
@Setter
@NoArgsConstructor
public class RecruitmentEligibility {

    private List<String> batches = new ArrayList<>();
}
