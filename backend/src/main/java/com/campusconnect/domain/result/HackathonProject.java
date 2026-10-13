package com.campusconnect.domain.result;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The project a hackathon entry submits (HackathonEntry.project). repoUrl is stage 2, the rest stage 3. */
@Getter
@Setter
@NoArgsConstructor
public class HackathonProject {

    private String title = "";
    private String summary = "";
    private String repoUrl = "";
    private String demoUrl = "";
    private String videoUrl = "";
    private String deckUrl = "";
    private String techStack = "";
}
