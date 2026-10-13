package com.campusconnect.domain.chat;

/** models/Message.js MESSAGE_TYPES. */
public enum MessageType {
    TEXT,
    MEDIA,
    FILE,
    VOICE,
    SYSTEM,
    /** A club election card in the club group (the election itself lives in ClubElection). */
    POLL
}
