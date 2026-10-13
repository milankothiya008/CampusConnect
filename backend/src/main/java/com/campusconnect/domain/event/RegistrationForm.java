package com.campusconnect.domain.event;

import com.campusconnect.domain.common.FormQuestion;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Optional registration form (Event.registrationForm): extra questions the club needs answered to register. The
 * student's name, e-mail, department and batch come from their account. Questions carry a {@code scope} (TEAM:
 * answered once by the team leader, MEMBER: by every member; Mongoose default MEMBER).
 */
@Getter
@Setter
@NoArgsConstructor
public class RegistrationForm {

    private boolean enabled = false;
    private List<FormQuestion> questions = new ArrayList<>();
}
