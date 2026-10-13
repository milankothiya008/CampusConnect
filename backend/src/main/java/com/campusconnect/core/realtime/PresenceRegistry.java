package com.campusconnect.core.realtime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Who is online, shared by all replicas (replaces ChatRealtime's in-memory maps).
 * <ul>
 *   <li>{@code cc:presence:<user>} — sorted set of that user's open sessions, scored by last refresh. A session
 *       counts while refreshed in the last 90 s; every replica refreshes its own sessions every 30 s, so a crashed
 *       replica's sessions fade out by themselves.</li>
 *   <li>{@code cc:watchers:<user>} — who wants presence updates about that user (expires after 10 minutes unless
 *       the watching tab is still open and refreshes it).</li>
 *   <li>{@code cc:viewing:<conversation>} — tabs that show that chat on screen right now.</li>
 * </ul>
 */
@Slf4j
@Component
public class PresenceRegistry {

    static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);
    static final Duration WATCH_TTL = Duration.ofMinutes(10);
    static final Duration VIEWING_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    /** This replica's sessions: session id -> user id, what it watches, what it views. */
    private final Map<String, String> localSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> localWatches = new ConcurrentHashMap<>();
    private final Map<String, String> localViewing = new ConcurrentHashMap<>();

    public PresenceRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String presenceKey(String userId) {
        return "cc:presence:" + userId;
    }

    private static String watchersKey(String userId) {
        return "cc:watchers:" + userId;
    }

    private static String viewingKey(String conversationId) {
        return "cc:viewing:" + conversationId;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** Records a new session; returns true when it is the user's first live session (they just came online). */
    public boolean connected(String userId, String sessionId) {
        localSessions.put(sessionId, userId);
        try {
            long before = liveCount(userId);
            redis.opsForZSet().add(presenceKey(userId), sessionId, now());
            redis.expire(presenceKey(userId), ONLINE_WINDOW.multipliedBy(2));
            return before == 0;
        } catch (RuntimeException e) {
            log.warn("Presence unavailable: {}", e.getMessage());
            return false;
        }
    }

    /** Forgets a session; returns true when the user has no live session left (they just went offline). */
    public boolean disconnected(String userId, String sessionId) {
        localSessions.remove(sessionId);
        localWatches.remove(sessionId);
        String viewing = localViewing.remove(sessionId);
        try {
            if (viewing != null) {
                redis.opsForZSet().remove(viewingKey(viewing), userId + "|" + sessionId);
            }
            redis.opsForZSet().remove(presenceKey(userId), sessionId);
            return liveCount(userId) == 0;
        } catch (RuntimeException e) {
            log.warn("Presence unavailable: {}", e.getMessage());
            return false;
        }
    }

    private long liveCount(String userId) {
        Long count = redis.opsForZSet().count(presenceKey(userId), now() - ONLINE_WINDOW.toMillis(), Double.POSITIVE_INFINITY);
        return count == null ? 0 : count;
    }

    public boolean isOnline(String userId) {
        try {
            return liveCount(userId) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The users among these with at least one live session (one pipelined round trip). */
    public Set<String> onlineAmong(Collection<String> userIds) {
        List<String> ids = new ArrayList<>(userIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        double min = now() - ONLINE_WINDOW.toMillis();
        try {
            List<Object> counts = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (String id : ids) {
                    connection.zSetCommands().zCount(bytes(presenceKey(id)), min, Double.POSITIVE_INFINITY);
                }
                return null;
            });
            Set<String> online = new LinkedHashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                if (counts.get(i) instanceof Number count && count.longValue() > 0) {
                    online.add(ids.get(i));
                }
            }
            return online;
        } catch (RuntimeException e) {
            // Without Redis, try everyone (the broker drops messages for users who aren't connected).
            return new LinkedHashSet<>(ids);
        }
    }

    /** This session wants presence updates about these people (replaces its previous list). */
    public void watch(String watcherId, String sessionId, Collection<String> targets) {
        Set<String> wanted = new LinkedHashSet<>(targets);
        localWatches.put(sessionId, wanted);
        writeWatches(watcherId, wanted);
    }

    private void writeWatches(String watcherId, Collection<String> targets) {
        if (targets.isEmpty()) {
            return;
        }
        double expiry = now() + WATCH_TTL.toMillis();
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (String target : targets) {
                    byte[] key = bytes(watchersKey(target));
                    connection.zSetCommands().zAdd(key, expiry, bytes(watcherId));
                    connection.keyCommands().expire(key, WATCH_TTL.toSeconds());
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("Presence watch unavailable: {}", e.getMessage());
        }
    }

    /** Users currently watching this person's presence. */
    public Set<String> watchersOf(String userId) {
        try {
            redis.opsForZSet().removeRangeByScore(watchersKey(userId), Double.NEGATIVE_INFINITY, now());
            Set<String> watchers = redis.opsForZSet().rangeByScore(watchersKey(userId), now(), Double.POSITIVE_INFINITY);
            return watchers == null ? Set.of() : watchers;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** The tab shows this chat (null when hidden or on another page). */
    public void viewing(String userId, String sessionId, String conversationId) {
        String previous = conversationId == null ? localViewing.remove(sessionId) : localViewing.put(sessionId, conversationId);
        String member = userId + "|" + sessionId;
        try {
            if (previous != null && !previous.equals(conversationId)) {
                redis.opsForZSet().remove(viewingKey(previous), member);
            }
            if (conversationId != null) {
                redis.opsForZSet().add(viewingKey(conversationId), member, now() + VIEWING_TTL.toMillis());
                redis.expire(viewingKey(conversationId), VIEWING_TTL);
            }
        } catch (RuntimeException e) {
            log.debug("Viewing registry unavailable: {}", e.getMessage());
        }
    }

    /** People who have this chat open on screen right now (they don't need a notification for it). */
    public Set<String> viewersOf(String conversationId) {
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(viewingKey(conversationId), now(), Double.POSITIVE_INFINITY);
            Set<String> users = new LinkedHashSet<>();
            if (members != null) {
                members.forEach(member -> users.add(member.substring(0, member.indexOf('|'))));
            }
            return users;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** Keeps this replica's sessions, watches and open chats alive in Redis (called every 30 s). */
    public void refreshLocal(boolean includeWatches) {
        if (localSessions.isEmpty()) {
            return;
        }
        long stamp = now();
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                localSessions.forEach((sessionId, userId) -> refreshSession(connection, userId, sessionId, stamp));
                localViewing.forEach((sessionId, conversationId) -> {
                    String userId = localSessions.get(sessionId);
                    if (userId != null) {
                        connection.zSetCommands().zAdd(bytes(viewingKey(conversationId)), stamp + VIEWING_TTL.toMillis(), bytes(userId + "|" + sessionId));
                        connection.keyCommands().expire(bytes(viewingKey(conversationId)), VIEWING_TTL.toSeconds());
                    }
                });
                return null;
            });
            if (includeWatches) {
                localWatches.forEach((sessionId, targets) -> {
                    String userId = localSessions.get(sessionId);
                    if (userId != null) {
                        writeWatches(userId, targets);
                    }
                });
            }
        } catch (RuntimeException e) {
            log.debug("Presence refresh failed: {}", e.getMessage());
        }
    }

    private static void refreshSession(RedisConnection connection, String userId, String sessionId, long stamp) {
        byte[] key = bytes(presenceKey(userId));
        connection.zSetCommands().zAdd(key, stamp, bytes(sessionId));
        connection.zSetCommands().zRemRangeByScore(key, Double.NEGATIVE_INFINITY, stamp - ONLINE_WINDOW.toMillis());
        connection.keyCommands().expire(key, ONLINE_WINDOW.multipliedBy(2).toSeconds());
    }

    public String userOfSession(String sessionId) {
        return localSessions.get(sessionId);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
