package com.officechat.office_chat_backend.config;

import com.officechat.office_chat_backend.auth.JwtService;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserService userService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new AuthenticationCredentialsNotFoundException("Missing Authorization header on CONNECT");
            }

            String token = authHeader.substring(7);
            User user = userService.loadUserByUsername(jwtService.extractEmail(token));

            if (!jwtService.isTokenValid(token, user)) {
                throw new AuthenticationCredentialsNotFoundException("Invalid or expired token");
            }

            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        }

        return message;
    }
}