package com.campusconnect.domain.result;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Everything about one event's results (models/EventResult.js): its rounds, and the final results (summary +
 * awards). {@code status}/{@code publishedAt} describe the final results; rounds carry their own. Any write must
 * clear the cached events feed (utils/Caches.js eventFeedCache).
 */
@Getter
@Setter
@NoArgsConstructor
@Document("eventresults")
@CompoundIndexes({
    @CompoundIndex(name = "status_1_publishedAt_-1", def = "{'status': 1, 'publishedAt': -1}"),
    @CompoundIndex(name = "club_1_status_1", def = "{'club': 1, 'status': 1}")
})
public class EventResult extends BaseDocument {

    @Indexed(name = "event_1", unique = true)
    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private String summary = "";
    private List<ResultAward> awards = new ArrayList<>();
    private List<ResultRound> rounds = new ArrayList<>();
    private ResultStatus status = ResultStatus.DRAFT;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    @Field(targetType = FieldType.OBJECT_ID)
    private String updatedBy;

    private Instant publishedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String publishedBy;

    /** Set when the president corrects final results after publishing them. */
    private Instant correctedAt;

    /** Most recent publication of anything (a round or the final results); orders the Results page. */
    @Indexed(name = "lastPublishedAt_-1", direction = IndexDirection.DESCENDING)
    private Instant lastPublishedAt;
}
