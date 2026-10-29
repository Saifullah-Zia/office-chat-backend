package com.officechat.office_chat_backend.presence;

import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        User user = extractUser(event.getUser());
        if (user == null) return;

        if (presenceService.userConnected(user.getId())) {
            messagingTemplate.convertAndSend("/topic/presence", PresenceEvent.online(user.getId()));
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        User user = extractUser(event.getUser());
        if (user == null) return;

        if (presenceService.userDisconnected(user.getId())) {
            Instant lastSeen = Instant.now();
            // The principal is a detached snapshot from login, so load a fresh row before saving.
            userRepository.findById(user.getId()).ifPresent(u -> {
                u.setLastSeenAt(lastSeen);
                userRepository.save(u);
            });
            messagingTemplate.convertAndSend("/topic/presence", PresenceEvent.offline(user.getId(), lastSeen));
        }
    }

    private User extractUser(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }
}
