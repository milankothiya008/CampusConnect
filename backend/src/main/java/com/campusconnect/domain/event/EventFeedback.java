package com.campusconnect.domain.event;

import com.campusconnect.domain.common.BaseDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A participant's rating (1–5 stars) and optional note after an event (models/EventFeedback.js). The club sees the
 * notes without names, so students can be honest.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("eventfeedbacks")
@CompoundIndexes({
    @CompoundIndex(name = "event_1_user_1", def = "{'event': 1, 'user': 1}", unique = true),
    @CompoundIndex(name = "club_1_createdAt_-1", def = "{'club': 1, 'createdAt': -1}")
})
public class EventFeedback extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private int rating;
    private String note = "";
}
