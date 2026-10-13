package com.campusconnect.domain.chat;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A message someone reported to the university admin (models/ChatReport.js). The text is copied in, so the report
 * survives the sender unsending it.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("chatreports")
@CompoundIndexes({
    @CompoundIndex(name = "status_1_createdAt_-1", def = "{'status': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "message_1_reporter_1", def = "{'message': 1, 'reporter': 1}", unique = true)
})
public class ChatReport extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String message;

    @Field(targetType = FieldType.OBJECT_ID)
    private String conversation;

    @Field(targetType = FieldType.OBJECT_ID)
    private String reporter;

    @Field(targetType = FieldType.OBJECT_ID)
    private String sender;

    private String reason = "";
    private String text = "";
    private int attachmentCount = 0;
    private ChatReportStatus status = ChatReportStatus.OPEN;

    @Field(targetType = FieldType.OBJECT_ID)
    private String resolvedBy;

    private Instant resolvedAt;
    private String note = "";
}
