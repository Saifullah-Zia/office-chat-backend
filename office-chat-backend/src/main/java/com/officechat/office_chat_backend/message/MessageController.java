package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.message.dto.MessageRequest;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.message.dto.TypingRequest;
import com.officechat.office_chat_backend.message.dto.TypingEvent;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageBroadcaster broadcaster;

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload MessageRequest request, Authentication authentication) {
        User sender = (User) authentication.getPrincipal();

        MessageResponse response = messageService.send(
                request.conversationId(), sender, request.content(), request.type(), request.attachmentId()
        );

        broadcaster.broadcastNew(response);
    }

    // Client sends to: /app/chat.typing
    // Server broadcasts to: /topic/conversation.{id}.typing
    @MessageMapping("/chat.typing")
    public void typing(@Payload TypingRequest request) {
        TypingEvent event = new TypingEvent(
                request.conversationId(), request.userId(), request.userName(), request.typing()
        );

        messagingTemplate.convertAndSend(
                "/topic/conversation." + request.conversationId() + ".typing", event
        );
    }
}