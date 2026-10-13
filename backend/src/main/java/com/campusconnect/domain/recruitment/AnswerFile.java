package com.campusconnect.domain.recruitment;

import com.campusconnect.domain.common.StoredMedia;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file answer (resume, portfolio) in media storage (ApplicationAnswer.file; no own _id): IMAGE or DOCUMENT,
 * provider "cloudinary" or "local". Only its description is kept here, like stories and gallery items.
 */
@Getter
@Setter
@NoArgsConstructor
public class AnswerFile extends StoredMedia {

    /** The original file name. */
    private String name;

    private Long bytes;
}
