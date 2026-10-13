package com.campusconnect.domain.social;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One row per person who watched a story (models/StoryView.js); the club's story managers see this list. The
 * Mongoose schema has neither timestamps nor a version key, so this is not a BaseDocument: just _id and the fields.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("storyviews")
@CompoundIndexes({
    @CompoundIndex(name = "story_1_user_1", def = "{'story': 1, 'user': 1}", unique = true),
    @CompoundIndex(name = "user_1_story_1_liked_1", def = "{'user': 1, 'story': 1, 'liked': 1}"),
    @CompoundIndex(name = "story_1_viewedAt_-1", def = "{'story': 1, 'viewedAt': -1}")
})
public class StoryView {

    @Id
    @JsonProperty("_id")
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String story;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    /** TTL backstop only: views are deleted together with their story. */
    @Indexed(name = "viewedAt_1", expireAfter = "8d")
    private Instant viewedAt = Instant.now();

    private boolean liked = false;
    private Instant likedAt;
}
