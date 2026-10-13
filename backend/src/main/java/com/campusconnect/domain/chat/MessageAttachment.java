package com.campusconnect.domain.chat;

import com.campusconnect.domain.common.MediaFile;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file attached to a chat message (Message.attachments; no own _id): IMAGE, VIDEO, AUDIO (voice notes) or
 * DOCUMENT. Only storage details are kept (see ChatMediaService); URLs are built on read.
 */
@Getter
@Setter
@NoArgsConstructor
public class MessageAttachment extends MediaFile {

    /** The original file name (documents). */
    private String name = "";
}
