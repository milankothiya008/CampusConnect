package com.campusconnect.domain.result;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.FormAnswer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One hackathon entry (models/HackathonEntry.js): a team (team events) or a single student (individual events).
 * It holds the chosen problem statement, the submitted project and every judge's scores.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("hackathonentries")
@CompoundIndexes({
    @CompoundIndex(name = "event_1_entryKey_1", def = "{'event': 1, 'entryKey': 1}", unique = true),
    @CompoundIndex(name = "event_1_problemStatement_1", def = "{'event': 1, 'problemStatement': 1}")
})
public class HackathonEntry extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    /** "team:&lt;id&gt;" or "user:&lt;id&gt;": one entry per team or per individual participant. */
    private String entryKey;

    @Field(targetType = FieldType.OBJECT_ID)
    private String team;

    @Field(targetType = FieldType.OBJECT_ID)
    private String owner;

    private String name;

    /** The chosen {@link ProblemStatement} _id. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String problemStatement;

    private Instant problemChosenAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String problemChosenBy;

    private HackathonProject project = new HackathonProject();

    // Stage 2: the code repository (project.repoUrl), due by the repository deadline.
    private Instant repoSubmittedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String repoSubmittedBy;

    // Stage 3: the final submission (name, description, demo, video, slides). Only these are judged.
    /** Answers to the organisers' extra submission questions. */
    private List<FormAnswer> submissionAnswers = new ArrayList<>();

    private Instant submittedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String submittedBy;

    private List<JudgeScore> scores = new ArrayList<>();
}
