package com.campusconnect.domain.result;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.FormQuestion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * The hackathon side of a HACKATHON event (models/Hackathon.js): agenda, problem statements (hidden until
 * revealAt), the deadlines for choosing a problem and submitting a project, judging criteria and the judges. Kept
 * apart from Event so problem statements never leak through the public event payload.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("hackathons")
@CompoundIndex(name = "judges.user_1", def = "{'judges.user': 1}")
public class Hackathon extends BaseDocument {

    @Indexed(name = "event_1", unique = true)
    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private List<AgendaItem> agenda = new ArrayList<>();
    private List<ProblemStatement> problemStatements = new ArrayList<>();

    /** Problem statements become visible to participants at revealAt (never before the event starts). */
    private Instant revealAt;

    // Three stages, each with its own deadline: choose a problem statement (selectionDeadline), register the code
    // repository (repoDeadline), then, once the repository deadline has passed, the final submission with the demo,
    // video and slides (submissionDeadline).
    private Instant selectionDeadline;
    private Instant repoDeadline;
    private Instant submissionDeadline;

    private List<JudgingCriterion> criteria = new ArrayList<>();
    private SubmissionFields submissionFields = new SubmissionFields();

    /** Extra questions for the final submission (e.g. "Which APIs did you use?"); no scope. */
    private List<FormQuestion> submissionQuestions = new ArrayList<>();

    private List<HackathonJudge> judges = new ArrayList<>();

    /** Time-based notifications already sent: REVEAL, SELECTION_1H, SUBMISSION_1H, JUDGING_OPEN. */
    private List<String> notified = new ArrayList<>();

    private Instant resultsDraftedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String updatedBy;
}
