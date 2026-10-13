package com.campusconnect.support;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A browser-like STOMP client for tests: connects to {@code /ws} with a bearer token, subscribes to
 * {@code /user/queue/events} and collects the {@code {event, payload}} frames.
 */
public final class StompTestClient implements AutoCloseable {

    private final WebSocketStompClient client;
    private final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<String> error = new CompletableFuture<>();
    private final CompletableFuture<StompSession> connected = new CompletableFuture<>();
    private StompSession session;

    private StompTestClient() {
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new CompositeMessageConverter(List.of(new ByteArrayMessageConverter(), new JacksonJsonMessageConverter())));
        this.client.setDefaultHeartbeat(new long[] {0, 0});
    }

    /** Connects (token may be null) without subscribing. */
    public static StompTestClient open(int port, String token) {
        StompTestClient test = new StompTestClient();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add("Origin", "http://localhost:3000");
        StompHeaders connect = new StompHeaders();
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        test.client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect, test.new Handler());
        return test;
    }

    /** Connects and subscribes to the user's event queue; fails if the server refuses. */
    public static StompTestClient connect(int port, String token) {
        StompTestClient test = open(port, token);
        try {
            test.session = test.connected.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("STOMP connect failed: " + test.error.getNow("no error frame"), e);
        }
        test.subscribe("/user/queue/events");
        return test;
    }

    public void subscribe(String destination) {
        session.subscribe(destination, new StompFrameHandler() {
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

    public void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    /** The next frame with this event name (others are skipped), or null after the timeout. */
    public Map<String, Object> next(String event, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> frame = frames.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (frame != null && event.equals(frame.get("event"))) {
                return frame;
            }
        }
        return null;
    }

    /** The message header of the ERROR frame the server sent, or null if none arrives in time. */
    public String errorMessage(Duration timeout) {
        try {
            return error.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    @Override
    public void close() {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException alreadyClosed) {
            // The server closed the session (e.g. after an ERROR frame).
        } finally {
            client.stop();
        }
    }

    private final class Handler extends StompSessionHandlerAdapter {

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(session);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String message = headers.getFirst("message");
            error.complete(message == null ? "ERROR" : message);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            if (command == StompCommand.ERROR) {
                error.complete(headers.getFirst("message"));
            }
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            connected.completeExceptionally(exception);
        }
    }
}
