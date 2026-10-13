package com.campusconnect.domain.audit;

import com.campusconnect.domain.common.BaseDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Who did what to which record (models/AuditLog.js). */
@Getter
@Setter
@NoArgsConstructor
@Document("auditlogs")
@CompoundIndexes({
    @CompoundIndex(
            name = "targetType_1_targetId_1_createdAt_-1",
            def = "{'targetType': 1, 'targetId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "actor_1_createdAt_-1", def = "{'actor': 1, 'createdAt': -1}")
})
public class AuditLog extends BaseDocument {

    private AuditAction action;

    @Field(targetType = FieldType.OBJECT_ID)
    private String actor;

    private String targetType;

    @Field(targetType = FieldType.OBJECT_ID)
    private String targetId;

    private String fromState;
    private String toState;
    private String reason;
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
