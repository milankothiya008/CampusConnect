package com.campusconnect.domain.result;

import com.campusconnect.domain.common.TimestampedSubDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A stage of a multi-round event (EventResult.rounds): screening, semi-final, … Each round is published on its own,
 * so results can go out while the event is still running. Has its own createdAt/updatedAt.
 */
@Getter
@Setter
@NoArgsConstructor
public class ResultRound extends TimestampedSubDocument {

    private String name;
    private String description = "";
    private List<ResultEntry> entries = new ArrayList<>();
    private ResultStatus status = ResultStatus.DRAFT;
    private Instant publishedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String publishedBy;

    /** Set when the president edits a round after publishing it. */
    private Instant correctedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    @Field(targetType = FieldType.OBJECT_ID)
    private String updatedBy;
}
