package com.campusconnect.core.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/** ERROR frames carry a short code only ({@code UNAUTHORIZED} or {@code BAD_REQUEST}) — never internals. */
public class RealtimeErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        boolean unauthorized = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RealtimeUnauthorizedException) {
                unauthorized = true;
                break;
            }
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(unauthorized ? "UNAUTHORIZED" : "BAD_REQUEST");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
