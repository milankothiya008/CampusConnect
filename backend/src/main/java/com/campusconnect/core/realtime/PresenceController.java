package com.campusconnect.core.realtime;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/** Client frames about presence and the chat on screen (/app/presence.watch, /app/viewing). */
@Controller
public class PresenceController {

    private final PresenceService presence;
    private final PresenceRegistry registry;
    private final RealtimeGateway gateway;

    public PresenceController(PresenceService presence, PresenceRegistry registry, RealtimeGateway gateway) {
        this.presence = presence;
        this.registry = registry;
        this.gateway = gateway;
    }

    public record WatchRequest(List<String> ids, String requestId) {
    }

    @MessageMapping("presence.watch")
    public void watch(@Payload WatchRequest request, Principal principal, @Header("simpSessionId") String sessionId) {
        if (!(principal instanceof StompPrincipal user)) {
            return;
        }
        List<Map<String, Object>> states;
        try {
            states = presence.watch(user.userId(), sessionId, request == null || request.ids() == null ? List.of() : request.ids());
        } catch (RuntimeException e) {
            states = List.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestId", request == null ? null : request.requestId());
        snapshot.put("states", states);
        gateway.emitToSession(user.userId(), sessionId, "presence:snapshot", snapshot);
    }

    public record ViewingRequest(String conversationId, Boolean visible) {
    }

    @MessageMapping("viewing")
    public void viewing(@Payload ViewingRequest request, Principal principal, @Header("simpSessionId") String sessionId) {
        if (!(principal instanceof StompPrincipal user)) {
            return;
        }
        String conversationId = request == null || request.conversationId() == null ? null : request.conversationId();
        if (conversationId != null && conversationId.length() > 40) {
            conversationId = conversationId.substring(0, 40);
        }
        boolean visible = request != null && !Boolean.FALSE.equals(request.visible());
        registry.viewing(user.userId(), sessionId, conversationId != null && visible ? conversationId : null);
    }
}
