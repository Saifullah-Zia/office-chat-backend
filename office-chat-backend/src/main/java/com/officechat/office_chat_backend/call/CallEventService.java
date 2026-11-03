package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageBroadcaster;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.message.MessageService;
import com.officechat.office_chat_backend.message.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Posts a small call entry into the chat when a call ends ("Missed voice call", "Video call · 4m 12s").
 * Written by the server for the caller, so it also appears in admin-only / announcement groups.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallEventService {

    private final MessageRepository messageRepository;
    private final MessageService messageService;
    private final MessageBroadcaster broadcaster;
    private final ConversationService conversationService;
    private final CallRinger callRinger;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void postEnded(Call call) {
        callRinger.callEnded(call.getId()); // anyone still being rung missed it
        notifyEnded(call);
        try {
            boolean video = call.getType() == CallType.VIDEO;
            String icon = video ? "🎥" : "📞";
            String kind = video ? "video call" : "voice call";
            String content = switch (call.getStatus()) {
                case MISSED -> icon + " Missed " + kind;
                case REJECTED -> icon + " Declined " + kind;
                default -> icon + " " + (video ? "Video call" : "Voice call")
                        + (call.getAnsweredAt() != null && call.getEndedAt() != null
                            ? " · " + duration(Duration.between(call.getAnsweredAt(), call.getEndedAt())) : "");
            };
            Long conversationId = call.getConversation().getId();
            Message m = messageRepository.save(Message.builder()
                    .conversation(call.getConversation())
                    .sender(call.getInitiator())
                    .content(content)
                    .type(MessageType.CALL)
                    .build());
            conversationService.unhideForAll(conversationId);
            broadcaster.broadcastNew(messageService.getMessageView(m.getId(), call.getInitiator().getId()));
        } catch (Exception e) {
            log.warn("Couldn't post the call entry for call {}: {}", call.getId(), e.getMessage());
        }
    }

    /** "This call is over" to everyone in the chat: an app that missed the other side's hang-up leaves too. */
    private void notifyEnded(Call call) {
        try {
            Long conversationId = call.getConversation().getId();
            java.util.Map<String, Object> ended = new java.util.LinkedHashMap<>();
            ended.put("type", "ENDED");
            ended.put("conversationId", conversationId);
            ended.put("callId", call.getId());
            for (String email : conversationService.getMemberEmails(conversationId)) {
                messagingTemplate.convertAndSendToUser(email, "/queue/call", ended);
            }
        } catch (Exception e) {
            log.warn("Couldn't send call-ended for call {}: {}", call.getId(), e.getMessage());
        }
    }

    private static String duration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s >= 3600) return (s / 3600) + "h " + (s % 3600 / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }
}
