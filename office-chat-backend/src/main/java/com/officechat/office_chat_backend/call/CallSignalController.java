package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.call.dto.CallSignalEvent;
import com.officechat.office_chat_backend.call.dto.CallSignalRequest;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class CallSignalController {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    // Client sends to: /app/call.signal
    // - If toUserId is set: relayed privately to that user's /user/queue/call
    // - If toUserId is null: broadcast to everyone in the conversation via /topic/conversation.{id}.call
    //   (used for ringing all members of a group call before anyone has joined)
    @MessageMapping("/call.signal")
    public void signal(@Payload CallSignalRequest request, Authentication authentication) {
        User fromUser = (User) authentication.getPrincipal();

        CallSignalEvent event = new CallSignalEvent(
                request.callId(),
                request.conversationId(),
                fromUser.getId(),
                fromUser.getName(),
                request.type(),
                request.payload()
        );

        if (request.toUserId() != null) {
            User targetUser = userRepository.findById(request.toUserId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Target user not found"));

            // convertAndSendToUser resolves to /user/{email}/queue/call — the email is the
            // STOMP principal name, set during the WebSocket handshake in WebSocketAuthInterceptor
            messagingTemplate.convertAndSendToUser(targetUser.getEmail(), "/queue/call", event);
        } else {
            messagingTemplate.convertAndSend(
                    "/topic/conversation." + request.conversationId() + ".call", event
            );
        }
    }
}