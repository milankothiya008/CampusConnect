package com.campusconnect.domain.social;

import com.campusconnect.domain.common.BaseDocument;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A post in the campus feed (models/FeedPost.js): an event, an announcement, an update or published results. */
@Getter
@Setter
@NoArgsConstructor
@Document("feedposts")
@CompoundIndexes({
    @CompoundIndex(name = "createdAt_-1", def = "{'createdAt': -1}"),
    @CompoundIndex(name = "recipients_1_createdAt_-1", def = "{'recipients': 1, 'createdAt': -1}", sparse = true),
    @CompoundIndex(name = "club_1_createdAt_-1", def = "{'club': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "type_1_createdAt_-1", def = "{'type': 1, 'createdAt': -1}")
})
public class FeedPost extends BaseDocument {

    private FeedPostType type;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    /** The EventResult of a RESULT post. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String result;

    @Field(targetType = FieldType.OBJECT_ID)
    private String author;

    private String title;
    private String body = "";
    private String image;
    private FeedVisibility visibility = FeedVisibility.PUBLIC;
    private boolean isSystem = false;

    /** Who the announcement was sent to; null for other posts. */
    private FeedAudience audience;

    /**
     * AUDIENCE posts: everyone it was sent to, so only they (and the club's team) can see it. Null (not stored) on
     * other posts, like Mongoose's {@code default: undefined}; never serialised ({@code select: false}).
     */
    @JsonIgnore
    @Field(targetType = FieldType.OBJECT_ID)
    private List<String> recipients;

    private Integer recipientCount;

    // "is…" boolean: keep the JSON name the frontend reads (post.isSystem); see User.
    @JsonProperty("isSystem")
    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean value) {
        this.isSystem = value;
    }
}
