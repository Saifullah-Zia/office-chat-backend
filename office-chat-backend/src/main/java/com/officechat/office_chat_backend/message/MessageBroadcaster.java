package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a NEW message to the conversation topic (for whoever has the chat open) and to each
 * member's private queue (/user/queue/messages), so sidebars can update unread counts, previews
 * and notifications for chats that aren't open.
 */
@Component
@RequiredArgsConstructor
public class MessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final ConversationService conversationService;
    private final com.officechat.office_chat_backend.push.PushService pushService;

    public void broadcastNew(MessageResponse message) {
        messagingTemplate.convertAndSend("/topic/conversation." + message.conversationId(), message);
        // Principal name is the user's email (see CallSignalController).
        conversationService.getMemberEmails(message.conversationId())
                .forEach(email -> messagingTemplate.convertAndSendToUser(email, "/queue/messages", message));
        pushService.notifyNewMessage(message);
    }
}
