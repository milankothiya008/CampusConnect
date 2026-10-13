package com.campusconnect.core.realtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * "Active now" (ChatRealtime presence): announces when someone's first tab connects or last tab closes, saves
 * {@code lastSeenAt}, and answers presence:watch. People can hide their status (Settings) — then they don't see
 * others' either, as on Instagram.
 */
@Slf4j
@Service
public class PresenceService {

    private final PresenceRegistry registry;
    private final RealtimeGateway gateway;
    private final MongoOperations mongo;

    public PresenceService(PresenceRegistry registry, RealtimeGateway gateway, MongoOperations mongo) {
        this.registry = registry;
        this.gateway = gateway;
        this.mongo = mongo;
    }

    /** {@code {userId, online, lastSeenAt}} or {@code {userId, hidden: true}}. */
    public static Map<String, Object> payload(String userId, Boolean showActivityStatus, Instant lastSeenAt, boolean online) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        if (Boolean.FALSE.equals(showActivityStatus)) {
            payload.put("hidden", true);
            return payload;
        }
        payload.put("online", online);
        payload.put("lastSeenAt", online ? null : lastSeenAt);
        return payload;
    }

    private Document presenceFields(String userId) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(userId)));
        query.fields().include("showActivityStatus").include("lastSeenAt");
        return mongo.findOne(query, Document.class, "users");
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        if (!(event.getUser() instanceof StompPrincipal principal)) {
            return;
        }
        String sessionId = (String) event.getMessage().getHeaders().get("simpSessionId");
        if (sessionId != null && registry.connected(principal.userId(), sessionId)) {
            Document user = presenceFields(principal.userId());
            if (user != null) {
                announce(principal.userId(), payload(principal.userId(), user.getBoolean("showActivityStatus"), null, true));
            }
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        if (!(event.getUser() instanceof StompPrincipal principal)) {
            return;
        }
        if (!registry.disconnected(principal.userId(), event.getSessionId())) {
            return;
        }
        Instant lastSeenAt = Instant.now();
        try {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(principal.userId()))), new Update().set("lastSeenAt", lastSeenAt), "users");
            Document user = presenceFields(principal.userId());
            if (user != null) {
                announce(principal.userId(), payload(principal.userId(), user.getBoolean("showActivityStatus"), lastSeenAt, false));
            }
        } catch (RuntimeException e) {
            log.warn("Could not save last seen: {}", e.getMessage());
        }
    }

    private void announce(String userId, Map<String, Object> payload) {
        gateway.emitToUsers(registry.watchersOf(userId), "presence", payload);
    }

    /** presence:watch — subscribe this tab to these people and return their current state. */
    public List<Map<String, Object>> watch(String watcherId, String sessionId, Collection<String> ids) {
        List<String> wanted = ids.stream().filter(ObjectId::isValid).distinct().limit(200).toList();
        registry.watch(watcherId, sessionId, wanted);
        Document viewer = presenceFields(watcherId);
        if (viewer != null && Boolean.FALSE.equals(viewer.getBoolean("showActivityStatus"))) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("_id").in(wanted.stream().map(ObjectId::new).toList()));
        query.fields().include("showActivityStatus").include("lastSeenAt");
        List<Document> people = mongo.find(query, Document.class, "users");
        var online = registry.onlineAmong(people.stream().map(person -> person.getObjectId("_id").toHexString()).toList());
        List<Map<String, Object>> states = new ArrayList<>();
        for (Document person : people) {
            String id = person.getObjectId("_id").toHexString();
            java.util.Date seen = person.getDate("lastSeenAt");
            states.add(payload(id, person.getBoolean("showActivityStatus"), seen == null ? null : seen.toInstant(), online.contains(id)));
        }
        return states;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        // Watches are re-written every 4th tick (2 minutes); sessions and open chats every 30 s.
        registry.refreshLocal(System.currentTimeMillis() / 30_000 % 4 == 0);
    }
}
