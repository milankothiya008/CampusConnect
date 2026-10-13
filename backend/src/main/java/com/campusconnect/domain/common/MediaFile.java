package com.campusconnect.domain.common;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A photo or video in media storage with its dimensions (EventMedia.media, Story.media; no own _id).
 * {@code duration} is in seconds (videos only).
 */
@Getter
@Setter
@NoArgsConstructor
public class MediaFile extends StoredMedia {

    private Integer width;
    private Integer height;
    private Double duration;
    private Long bytes;
}
