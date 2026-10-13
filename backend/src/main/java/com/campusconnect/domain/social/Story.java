package com.campusconnect.domain.social;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.MediaFile;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A club story that lives for 24 hours (models/Story.js). Only its description is stored here: the photo or video
 * (IMAGE or VIDEO; provider "cloudinary", "local" or "event" for the linked event's poster) sits in media storage and
 * is served to viewers from there, never through this database.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("stories")
@CompoundIndexes({
    @CompoundIndex(name = "expiresAt_1_club_1_createdAt_1", def = "{'expiresAt': 1, 'club': 1, 'createdAt': 1}"),
    @CompoundIndex(name = "club_1_expiresAt_1", def = "{'club': 1, 'expiresAt': 1}")
})
public class Story extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String author;

    private MediaFile media = new MediaFile();
    private String caption = "";

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    private int viewCount = 0;
    private int likeCount = 0;

    /** TTL backstop only: the sweeper normally deletes a story (and its media) within minutes of expiry. */
    @Indexed(name = "story_ttl_backstop", expireAfter = "7d")
    private Instant expiresAt;

    /** Failed attempts to remove the media from storage after expiry (the sweeper gives up after a few). */
    private int cleanupAttempts = 0;
}
