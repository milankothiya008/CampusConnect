package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.SubDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One question of a role's application form (RecruitmentPage.questions). The applicant's name, e-mail, department
 * and batch come from their profile and are never asked again.
 */
@Getter
@Setter
@NoArgsConstructor
public class RecruitmentQuestion extends SubDocument {

    private QuestionType type;
    private String label;
    private String help = "";
    private boolean required = false;
    private List<String> options = new ArrayList<>();
}
