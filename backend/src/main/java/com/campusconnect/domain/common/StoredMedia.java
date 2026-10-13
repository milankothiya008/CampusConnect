package com.campusconnect.domain.common;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file in media storage, described by where it lives (no own _id). The bytes never pass through the database:
 * viewer URLs are built from these details on every read (services/MediaStore.js mediaUrls). This is exactly the
 * chat avatar's shape (models/Conversation.js mediaSchema); {@link MediaFile} and the chat attachment / recruitment
 * answer file types add details.
 */
@Getter
@Setter
@NoArgsConstructor
public class StoredMedia {

    private MediaKind kind;

    /**
     * {@code "cloudinary"} (production), {@code "local"} (files under UPLOAD_DIR, development) or, for stories only,
     * {@code "event"} (the linked event's poster, never deleted by the story). Lower-case, as stored by the Node app.
     */
    private String provider;

    /** Cloudinary public ID, path under UPLOAD_DIR, or (provider "event") the poster URL. */
    private String key;

    /** Cloudinary version. */
    private Long version;

    private String format;
}
