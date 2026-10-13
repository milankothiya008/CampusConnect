package com.campusconnect.feature.chat;

import com.campusconnect.core.realtime.StompPrincipal;
import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * Client frames of the chat (docs/REALTIME.md): {@code /app/typing} and {@code /app/delivered}, the Socket.IO
 * {@code typing} / {@code delivered} events. Like the original handlers they never answer and never fail the
 * connection: a bad frame is simply ignored.
 */
@Slf4j
@Controller
public class ChatRealtimeController {

    private final ChatSignals signals;

    public ChatRealtimeController(ChatSignals signals) {
        this.signals = signals;
    }

    /** {@code { conversationId, recording? }}; {@code recording} is read the JavaScript way (any truthy value). */
    public record TypingFrame(String conversationId, Object recording) {
    }

    public record DeliveredFrame(String conversationId) {
    }

    @MessageMapping("typing")
    public void typing(@Payload(required = false) TypingFrame frame, Principal principal) {
        if (!(principal instanceof StompPrincipal user) || frame == null) {
            return;
        }
        try {
            signals.typing(user.userId(), frame.conversationId(), JsValues.truthy(frame.recording()));
        } catch (RuntimeException e) {
            log.debug("Typing signal dropped: {}", e.getMessage());
        }
    }

    @MessageMapping("delivered")
    public void delivered(@Payload(required = false) DeliveredFrame frame, Principal principal) {
        if (!(principal instanceof StompPrincipal user) || frame == null) {
            return;
        }
        try {
            signals.delivered(user.userId(), frame.conversationId());
        } catch (RuntimeException e) {
            log.debug("Delivered signal dropped: {}", e.getMessage());
        }
    }
}
