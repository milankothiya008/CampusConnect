package com.campusconnect.core.realtime;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.security.JwtTokens;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Guards every frame a client sends (ChatRealtime.authenticate):
 * <ul>
 *   <li>CONNECT needs {@code Authorization: Bearer <access token>} of an active, verified account;</li>
 *   <li>SUBSCRIBE is only allowed to the caller's own event queue ({@code /user/queue/events}); with the RabbitMQ
 *       relay it is mapped to an auto-deleted exclusive queue so nothing is left behind after disconnect;</li>
 *   <li>SEND is only allowed to {@code /app/...} handlers (never straight to broker destinations).</li>
 * </ul>
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    public static final String EVENTS = "/user/queue/events";
    /** What {@link #EVENTS} becomes with the RabbitMQ relay (see {@link RealtimeGateway}). */
    public static final String RELAY_EVENTS = "/user/exchange/amq.direct/events";

    private final JwtTokens tokens;
    private final AuthUserService users;
    private final boolean relay;

    public StompAuthInterceptor(JwtTokens tokens, AuthUserService users, boolean relay) {
        this.tokens = tokens;
        this.users = users;
        this.relay = relay;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
            case SUBSCRIBE -> {
                requireUser(accessor);
                if (!EVENTS.equals(accessor.getDestination())) {
                    throw new RealtimeUnauthorizedException();
                }
                if (relay) {
                    accessor.setDestination(RELAY_EVENTS);
                }
            }
            case SEND -> {
                requireUser(accessor);
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new RealtimeUnauthorizedException();
                }
            }
            default -> {
            }
        }
        return message;
    }

    private void requireUser(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal)) {
            throw new RealtimeUnauthorizedException();
        }
    }

    private StompPrincipal authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new RealtimeUnauthorizedException();
        }
        try {
            Jwt jwt = tokens.verify(header.substring(7).trim());
            AuthUser user = users.find(jwt.getSubject()).orElse(null);
            if (user == null || !user.active() || !user.emailVerified()) {
                throw new RealtimeUnauthorizedException();
            }
            return new StompPrincipal(user.id());
        } catch (RealtimeUnauthorizedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RealtimeUnauthorizedException();
        }
    }
}
