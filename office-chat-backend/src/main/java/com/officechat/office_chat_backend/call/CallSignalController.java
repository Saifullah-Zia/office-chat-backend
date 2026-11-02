package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Call signalling relay (ring / answer / hang up). Clients send to /app/call.signal; the server checks
 * that both people are in the conversation and allowed to talk, stamps the real sender, and delivers
 * to the recipient's /user/queue/call. Clients can no longer write to each other's queues directly.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CallSignalController {

    private static final Set<String> TYPES = Set.of("OFFER", "ANSWER", "ICE_CANDIDATE", "HANGUP", "BUSY");
    private static final int MAX_FIELDS = 20;

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final ConversationService conversationService;

    @MessageMapping("/call.signal")
    public void signal(@Payload Map<String, Object> body, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User from)) return;
        Long conversationId = asLong(body.get("conversationId"));
        Long toUserId = asLong(body.get("toUserId"));
        String type = String.valueOf(body.get("type"));
        if (conversationId == null || toUserId == null || !TYPES.contains(type) || body.size() > MAX_FIELDS
                || toUserId.equals(from.getId())) {
            return;
        }
        // Both must be in the chat, not blocked, and providers never ring other providers.
        if (!conversationService.isMember(conversationId, from.getId())
                || !conversationService.isMember(conversationId, toUserId)
                || conversationService.isBlockedBetween(from.getId(), toUserId)
                || (conversationService.isProvider(from.getId()) && conversationService.isProvider(toUserId))) {
            log.debug("Dropped {} call signal from {} to {} in {}", type, from.getId(), toUserId, conversationId);
            return;
        }
        User to = userRepository.findById(toUserId).filter(User::isActive).orElse(null);
        if (to == null) return;

        Map<String, Object> event = new LinkedHashMap<>(body);
        // Never trust who the client says it is.
        event.put("fromUserId", from.getId());
        event.put("fromUserName", from.getName());
        // Principal name is the email (see WebSocketAuthInterceptor), so this reaches only that user.
        messagingTemplate.convertAndSendToUser(to.getEmail(), "/queue/call", event);
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
