package com.campusconnect.domain.event;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.MediaFile;
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
 * A photo or video in an event's gallery (models/EventMedia.js). Only the description lives here: the file sits in
 * media storage (IMAGE or VIDEO; provider "cloudinary" or "local"). Rejected uploads are deleted outright.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("eventmedias")
@CompoundIndexes({
    @CompoundIndex(name = "event_1_status_1_createdAt_-1", def = "{'event': 1, 'status': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "club_1_status_1", def = "{'club': 1, 'status': 1}"),
    @CompoundIndex(name = "event_1_uploader_1_status_1", def = "{'event': 1, 'uploader': 1, 'status': 1}")
})
public class EventMedia extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String uploader;

    /** How the uploader was allowed to add it: as a club member or as a checked-in participant. */
    private UploaderRole uploaderRole;

    private MediaFile media = new MediaFile();
    private GalleryStatus status = GalleryStatus.PENDING;
    private int likeCount = 0;

    @Field(targetType = FieldType.OBJECT_ID)
    private String reviewedBy;

    private Instant reviewedAt;
}
