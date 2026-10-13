package com.campusconnect.domain.notification;

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

/** An in-app notification (models/Notification.js). */
@Getter
@Setter
@NoArgsConstructor
@Document("notifications")
@CompoundIndexes({
    @CompoundIndex(name = "user_1_createdAt_-1", def = "{'user': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "user_1_readAt_1", def = "{'user': 1, 'readAt': 1}")
})
public class Notification extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private NotificationType type;
    private String title;
    private String message = "";
    private String link;
    private Instant readAt;
}
