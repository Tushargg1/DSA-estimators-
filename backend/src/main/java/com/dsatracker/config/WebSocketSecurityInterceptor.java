package com.dsatracker.config;

import com.dsatracker.security.AccessService;
import com.dsatracker.security.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Authenticates STOMP CONNECT and limits subscriptions to the caller's groups. */
@Component
public class WebSocketSecurityInterceptor implements ChannelInterceptor {
    private static final Pattern GROUP_TOPIC = Pattern.compile("^/topic/group/(\\d+)$");
    private final TokenService tokens;
    private final AccessService access;

    public WebSocketSecurityInterceptor(TokenService tokens, AccessService access) {
        this.tokens = tokens;
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        if (command == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
                throw new AccessDeniedException("Authentication required");
            }
            try {
                accessor.setUser(tokens.authenticate(header.substring(7)));
            } catch (RuntimeException ex) {
                throw new AccessDeniedException("Authentication required");
            }
        } else if (command == StompCommand.SUBSCRIBE) {
            Authentication principal = requirePrincipal(accessor);
            Matcher matcher = GROUP_TOPIC.matcher(String.valueOf(accessor.getDestination()));
            if (!matcher.matches()
                    || !access.isGroupMember(Long.valueOf(principal.getName()), Long.valueOf(matcher.group(1)))) {
                throw new AccessDeniedException("Subscription denied");
            }
        } else if (command == StompCommand.SEND) {
            throw new AccessDeniedException("Client messages are not supported");
        }
        return message;
    }

    private static Authentication requirePrincipal(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof Authentication authentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        return authentication;
    }
}
