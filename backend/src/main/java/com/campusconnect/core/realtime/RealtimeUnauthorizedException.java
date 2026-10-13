package com.campusconnect.core.realtime;

import org.springframework.messaging.MessagingException;

/** Rejects a STOMP frame; the client receives an ERROR frame whose message header is {@code UNAUTHORIZED}. */
public class RealtimeUnauthorizedException extends MessagingException {

    public RealtimeUnauthorizedException() {
        super("UNAUTHORIZED");
    }
}
