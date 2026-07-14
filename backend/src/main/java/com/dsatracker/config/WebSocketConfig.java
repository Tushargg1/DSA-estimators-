package com.dsatracker.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for live leaderboard updates (task 9.1,
 * Requirement 7.1; design.md "WebSocket Design").
 *
 * <p>Clients connect to the {@code /ws} endpoint and subscribe to their group's
 * channel {@code /topic/group/{groupId}}. The backend never receives inbound
 * STOMP messages from clients — updates are one-directional pushes from the
 * polling job (see {@link com.dsatracker.service.PollingService}) — so the
 * application destination prefix is configured but unused by design.
 *
 * <ul>
 *   <li><b>Endpoint:</b> {@code /ws}, registered both with SockJS fallback (for
 *       browsers/proxies that cannot hold a raw WebSocket) and as a plain
 *       WebSocket endpoint.</li>
 *   <li><b>Broker:</b> the built-in simple broker relays messages to
 *       destinations prefixed {@code /topic} (one topic per group).</li>
 *   <li><b>App prefix:</b> {@code /app} is set for completeness; no
 *       {@code @MessageMapping} handlers exist because clients only subscribe.</li>
 * </ul>
 *
 * <p><b>Origins (task 12.4):</b> the WebSocket handshake allowed origins are
 * env-driven, read from the {@code websocket.allowed-origins} property (bound to
 * the {@code WEBSOCKET_ALLOWED_ORIGINS} env var via Spring's relaxed binding).
 * It defaults to the Vite dev server ({@code http://localhost:5173}) so local
 * development needs no extra config. The wildcard default is intentionally gone:
 * production MUST set {@code WEBSOCKET_ALLOWED_ORIGINS} to the explicit frontend
 * origin(s) — never {@code *} — see DEPLOYMENT.md / .env.example. REST CORS is
 * configured separately in {@link CorsConfig}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] allowedOrigins;

    public WebSocketConfig(
            @Value("${websocket.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Plain WebSocket endpoint. Origins are the explicit env-configured list
        // (task 12.4) — setAllowedOriginPatterns accepts exact origins as well as
        // patterns, so this works for both localhost dev and prod origins.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins);

        // Same endpoint with SockJS fallback for clients that cannot use a raw
        // WebSocket (older browsers, restrictive proxies).
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker: relays to per-group topics /topic/group/{id}.
        registry.enableSimpleBroker("/topic");
        // App-destination prefix is unused (clients only subscribe) but set for
        // completeness / future @MessageMapping handlers.
        registry.setApplicationDestinationPrefixes("/app");
    }
}
