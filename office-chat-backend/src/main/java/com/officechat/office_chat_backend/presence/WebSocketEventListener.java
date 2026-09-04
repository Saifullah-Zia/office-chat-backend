package com.officechat.office_chat_backend.presence;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

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
            messagingTemplate.convertAndSend("/topic/presence", PresenceEvent.offline(user.getId()));
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