package com.campusconnect.feature.chat;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A browser tab for the tests: STOMP over {@code ws://localhost:<port>/ws} with the user's bearer token (the React
 * app's chatSocket), subscribed to {@code /user/queue/events}, collecting the {@code {event, payload}} frames.
 */
final class ChatStompClient implements AutoCloseable {

    private static final AtomicInteger PROBES = new AtomicInteger();

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    private final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
    private final StompSession session;

    private ChatStompClient(int port, String token) {
        client.setMessageConverter(new CompositeMessageConverter(List.of(new ByteArrayMessageConverter(), new JacksonJsonMessageConverter())));
        client.setDefaultHeartbeat(new long[] {0, 0});
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add("Origin", "http://localhost:3000");
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        CompletableFuture<StompSession> connected = client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect, new StompSessionHandlerAdapter() {
        });
        try {
            session = connected.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            client.stop();
            throw new IllegalStateException("STOMP connect failed", e);
        }
        session.subscribe("/user/queue/events", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((Map<String, Object>) payload);
            }
        });
    }

    /** Connects, subscribes and waits until the subscription is live (a presence probe comes back to this tab). */
    static ChatStompClient connect(int port, String token) throws InterruptedException {
        ChatStompClient tab = new ChatStompClient(port, token);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String requestId = "probe-" + PROBES.incrementAndGet();
            tab.send("/app/presence.watch", Map.of("ids", List.of(), "requestId", requestId));
            Map<String, Object> answer = tab.next("presence:snapshot", Duration.ofMillis(300));
            if (answer != null && answer.get("payload") instanceof Map<?, ?> payload && requestId.equals(payload.get("requestId"))) {
                return tab;
            }
        }
        tab.close();
        throw new IllegalStateException("The event subscription never became live");
    }

    void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    /** The next frame with this event name (others are skipped), or null after the timeout. */
    Map<String, Object> next(String event, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            Map<String, Object> frame = frames.poll(left, TimeUnit.NANOSECONDS);
            if (frame != null && event.equals(frame.get("event"))) {
                return frame;
            }
        }
    }

    /** Every frame of this event that arrives within the time (for "nothing was sent" checks). */
    List<Map<String, Object>> all(String event, Duration within) throws InterruptedException {
        List<Map<String, Object>> found = new ArrayList<>();
        long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return found;
            }
            Map<String, Object> frame = frames.poll(left, TimeUnit.NANOSECONDS);
            if (frame != null && event.equals(frame.get("event"))) {
                found.add(frame);
            }
        }
    }

    @Override
    public void close() {
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException alreadyClosed) {
            // The server closed the session.
        } finally {
            client.stop();
        }
    }
}
