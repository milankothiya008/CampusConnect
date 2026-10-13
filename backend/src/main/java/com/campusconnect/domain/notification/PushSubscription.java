package com.campusconnect.domain.notification;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A browser or phone where a user switched on Web Push (models/PushSubscription.js). */
@Getter
@Setter
@NoArgsConstructor
@Document("pushsubscriptions")
public class PushSubscription extends BaseDocument {

    @Indexed(name = "user_1")
    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    @Indexed(name = "endpoint_1", unique = true)
    private String endpoint;

    private Keys keys = new Keys();
    private String userAgent = "";
    private Instant lastSuccessAt;
    private int failures = 0;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Keys {
        private String p256dh;
        private String auth;
    }
}
