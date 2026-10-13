package com.campusconnect.core.realtime;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.security.JwtTokens;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket at {@code /ws} (docs/REALTIME.md). With {@code app.realtime.broker=relay} messages go
 * through RabbitMQ's STOMP plugin, so a user is reached whichever replica holds the connection; {@code simple}
 * keeps everything in memory (one node, tests).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;
    private final JwtTokens tokens;
    private final AuthUserService users;

    public WebSocketConfig(AppProperties properties, JwtTokens tokens, AuthUserService users) {
        this.properties = properties;
        this.tokens = tokens;
        this.users = users;
    }

    boolean relay() {
        return "relay".equalsIgnoreCase(properties.getRealtime().getBroker());
    }

    @Bean
    ThreadPoolTaskScheduler realtimeHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(properties.getClientUrl().replaceAll("/$", ""));
        registry.setErrorHandler(new RealtimeErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
        if (relay()) {
            AppProperties.Realtime realtime = properties.getRealtime();
            registry.enableStompBrokerRelay("/topic", "/queue", "/exchange")
                    .setRelayHost(realtime.getRelayHost())
                    .setRelayPort(realtime.getRelayPort())
                    .setClientLogin(realtime.getRelayLogin())
                    .setClientPasscode(realtime.getRelayPasscode())
                    .setSystemLogin(realtime.getRelayLogin())
                    .setSystemPasscode(realtime.getRelayPasscode())
                    .setVirtualHost(realtime.getRelayVirtualHost())
                    .setSystemHeartbeatSendInterval(10_000)
                    .setSystemHeartbeatReceiveInterval(10_000)
                    // Several replicas: share who is connected where, and let others resolve unknown users.
                    .setUserDestinationBroadcast("/topic/cc.unresolved-user")
                    .setUserRegistryBroadcast("/topic/cc.user-registry");
        } else {
            registry.enableSimpleBroker("/topic", "/queue", "/exchange")
                    .setHeartbeatValue(new long[] {10_000, 10_000})
                    .setTaskScheduler(realtimeHeartbeatScheduler());
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(tokens, users, relay()));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Like Socket.IO's maxHttpBufferSize: frames are small JSON events.
        registration.setMessageSizeLimit(64 * 1024);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(20_000);
    }
}
