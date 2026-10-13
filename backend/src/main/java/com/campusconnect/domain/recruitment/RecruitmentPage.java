package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.SubDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A page of an application form (PositionForm.pages): applicants fill one page, then Next. */
@Getter
@Setter
@NoArgsConstructor
public class RecruitmentPage extends SubDocument {

    private String title;
    private String description = "";
    private List<RecruitmentQuestion> questions = new ArrayList<>();
}
