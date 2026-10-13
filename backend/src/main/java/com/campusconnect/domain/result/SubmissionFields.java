package com.campusconnect.domain.result;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which standard fields a hackathon's final submission asks for (Hackathon.submissionFields), decided by the
 * organisers. Name and description are always asked.
 */
@Getter
@Setter
@NoArgsConstructor
public class SubmissionFields {

    private SubmissionFieldSetting demoUrl = SubmissionFieldSetting.OPTIONAL;
    private SubmissionFieldSetting videoUrl = SubmissionFieldSetting.OPTIONAL;
    private SubmissionFieldSetting deckUrl = SubmissionFieldSetting.OPTIONAL;
    private SubmissionFieldSetting techStack = SubmissionFieldSetting.OPTIONAL;
}
