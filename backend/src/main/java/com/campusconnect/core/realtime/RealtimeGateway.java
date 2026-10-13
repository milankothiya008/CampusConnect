package com.campusconnect.core.realtime;

import com.campusconnect.core.config.AppProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends {@code { event, payload }} frames to every open tab of some users (ChatRealtime.emitToUsers). Users who
 * are offline everywhere are skipped up front (Redis presence), so big fan-outs only touch live sessions.
 */
@Slf4j
@Component
public class RealtimeGateway {

    private final SimpMessagingTemplate messaging;
    private final PresenceRegistry presence;
    private final String destination;

    public RealtimeGateway(SimpMessagingTemplate messaging, PresenceRegistry presence, AppProperties properties) {
        this.messaging = messaging;
        this.presence = presence;
        this.destination = "relay".equalsIgnoreCase(properties.getRealtime().getBroker()) ? "/exchange/amq.direct/events" : "/queue/events";
    }

    public void emitToUsers(Collection<String> userIds, String event, Object payload) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        userIds.forEach(id -> {
            if (id != null) {
                unique.add(id);
            }
        });
        Map<String, Object> frame = frame(event, payload);
        for (String userId : presence.onlineAmong(unique)) {
            try {
                messaging.convertAndSendToUser(userId, destination, frame);
            } catch (RuntimeException e) {
                log.debug("Realtime emit failed ({}): {}", event, e.getMessage());
            }
        }
    }

    public void emitToUser(String userId, String event, Object payload) {
        if (userId != null) {
            emitToUsers(Set.of(userId), event, payload);
        }
    }

    /** Only one tab (session) of the user — e.g. the answer to that tab's presence:watch. */
    public void emitToSession(String userId, String sessionId, String event, Object payload) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messaging.convertAndSendToUser(userId, destination, frame(event, payload), headers.getMessageHeaders());
    }

    private static Map<String, Object> frame(String event, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", event);
        frame.put("payload", payload);
        return frame;
    }
}
