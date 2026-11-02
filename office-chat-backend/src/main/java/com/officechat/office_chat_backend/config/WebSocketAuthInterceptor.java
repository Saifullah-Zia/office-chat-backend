package com.officechat.office_chat_backend.config;

import com.officechat.office_chat_backend.auth.JwtService;
import com.officechat.office_chat_backend.conversation.ConversationMemberRepository;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    /** Company-wide topics any signed-in user may listen to. */
    private static final Set<String> PUBLIC_TOPICS = Set.of(
            "/topic/presence", "/topic/user.status", "/topic/user.updated", "/topic/user.block", "/topic/user.removed");
    /** /topic/conversation.{id} and /topic/conversation.{id}.{typing|read|ack|...}: members only. */
    private static final Pattern CONVERSATION_TOPIC = Pattern.compile("^/topic/conversation\\.(\\d+)(\\.[a-z]+)?$");

    private final JwtService jwtService;
    private final UserService userService;
    private final com.officechat.office_chat_backend.auth.SessionService sessionService;
    private final ConversationMemberRepository memberRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // getAccessor (not wrap): the user set below must stick to THIS message, or the session has no principal
        // and @MessageMapping handlers / /user/... destinations don't know who is connected.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new AuthenticationCredentialsNotFoundException("Missing Authorization header on CONNECT");
            }

            String token = authHeader.substring(7);
            User user = userService.loadUserByUsername(jwtService.extractEmail(token));

            if (!jwtService.isTokenValid(token, user)
                    || !user.isActive()
                    || !sessionService.isActive(jwtService.extractSessionId(token))) {
                throw new AuthenticationCredentialsNotFoundException("Invalid or expired token");
            }

            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            return message;
        }

        // Refused frames are dropped (returning null), not errored: an ERROR frame would close the whole connection.
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            String dest = accessor.getDestination();
            // Clients may only talk to the server (@MessageMapping), never straight into another user's queue or a topic.
            if (dest == null || !dest.startsWith("/app/")) {
                log.debug("Dropped client SEND to {}", dest);
                return null;
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (!maySubscribe(accessor.getDestination(), currentUser(accessor.getUser()))) {
                log.debug("Dropped SUBSCRIBE to {}", accessor.getDestination());
                return null;
            }
        }
        return message;
    }

    private boolean maySubscribe(String dest, User user) {
        if (dest == null || user == null) return false;
        if (dest.startsWith("/user/queue/")) return true; // Spring routes these to the connected user only
        if (PUBLIC_TOPICS.contains(dest)) return true;
        Matcher m = CONVERSATION_TOPIC.matcher(dest);
        return m.matches() && memberRepository.existsByConversationIdAndUserId(Long.parseLong(m.group(1)), user.getId());
    }

    private static User currentUser(Principal principal) {
        return principal instanceof UsernamePasswordAuthenticationToken t && t.getPrincipal() instanceof User u ? u : null;
    }
}
