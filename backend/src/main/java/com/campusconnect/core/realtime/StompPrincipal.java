package com.campusconnect.core.realtime;

import java.security.Principal;

/** The signed-in user of a WebSocket session; its name is the user id (the user destination key). */
public record StompPrincipal(String userId) implements Principal {

    @Override
    public String getName() {
        return userId;
    }
}
