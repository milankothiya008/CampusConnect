package com.campusconnect.domain.recruitment;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A role's application form (RecruitmentPosition.form), page-wise. */
@Getter
@Setter
@NoArgsConstructor
public class PositionForm {

    private List<RecruitmentPage> pages = new ArrayList<>();
}
