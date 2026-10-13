package com.campusconnect.domain.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Proposed changes to a published event (Event.revision; no own _id). The live event stays as it is until the
 * faculty mentor approves the changes and the club publishes them (EventService revision functions).
 *
 * <p>{@code changes} is free-form (Mongoose Mixed): the proposed new values by event field name. Values read back
 * from MongoDB are BSON types ({@code java.util.Date}, {@code ObjectId}, {@code org.bson.Document}, lists), not the
 * types of the matching {@link Event} fields.
 */
@Getter
@Setter
@NoArgsConstructor
public class EventRevision {

    private RevisionStatus status;
    private Map<String, Object> changes = new LinkedHashMap<>();

    /** Names of the changed fields. */
    private List<String> fields = new ArrayList<>();

    private String note = "";

    @Field(targetType = FieldType.OBJECT_ID)
    private String requestedBy;

    private Instant requestedAt = Instant.now();
    private String reviewComment;

    @Field(targetType = FieldType.OBJECT_ID)
    private String reviewedBy;

    private Instant reviewedAt;
}
