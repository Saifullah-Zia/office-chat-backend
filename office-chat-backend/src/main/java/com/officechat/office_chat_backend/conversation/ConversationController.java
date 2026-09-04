package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.conversation.dto.ConversationResponse;
import com.officechat.office_chat_backend.conversation.dto.CreateDirectRequest;
import com.officechat.office_chat_backend.conversation.dto.CreateGroupRequest;
import com.officechat.office_chat_backend.message.MessageService;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping
    public List<ConversationResponse> getMyConversations(@AuthenticationPrincipal User currentUser) {
        return conversationService.getUserConversations(currentUser.getId());
    }

    @PostMapping("/direct")
    public ConversationResponse createDirect(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CreateDirectRequest request
    ) {
        return conversationService.getOrCreateDirect(currentUser.getId(), request.otherUserId());
    }

    @PostMapping("/group")
    public ConversationResponse createGroup(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CreateGroupRequest request
    ) {
        return conversationService.createGroup(currentUser.getId(), request.name(), request.memberIds());
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> getMessages(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        try {
            return messageService.getHistory(conversationId, currentUser.getId(), page, size);
        } catch (Exception e) {
            System.err.println("GET MESSAGES ERROR: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @PostMapping("/{id}/messages")
    public MessageResponse sendMessage(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody com.officechat.office_chat_backend.message.dto.MessageRequest request
    ) {
        MessageResponse response = messageService.send(
                conversationId, currentUser, request.content(), request.type(), request.attachmentId()
        );
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }

    @PostMapping("/{id}/read")
    public void markAsRead(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId
    ) {
        conversationService.markAsRead(conversationId, currentUser.getId());
        messagingTemplate.convertAndSend(
                "/topic/conversation." + conversationId + ".read",
                (Object) java.util.Map.of("conversationId", conversationId, "userId", currentUser.getId())
        );
    }

    @PostMapping("/{id}/members")
    public ConversationResponse addMember(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Long> body
    ) {
        Long newUserId = body.get("userId");
        return conversationService.addMemberToGroup(conversationId, currentUser.getId(), newUserId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ConversationResponse removeMember(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("userId") Long targetUserId
    ) {
        return conversationService.removeMemberFromGroup(conversationId, currentUser.getId(), targetUserId);
    }

    @PostMapping("/{id}/messages/{messageId}/reactions")
    public MessageResponse toggleReaction(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("messageId") Long messageId,
            @RequestBody java.util.Map<String, String> body
    ) {
        String emoji = body.get("emoji");
        MessageResponse response = messageService.toggleReaction(messageId, currentUser, emoji);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }

    @PutMapping("/{id}/messages/{messageId}")
    public MessageResponse editMessage(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("messageId") Long messageId,
            @RequestBody java.util.Map<String, String> body
    ) {
        String newContent = body.get("content");
        MessageResponse response = messageService.editMessage(messageId, currentUser, newContent);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    public MessageResponse deleteMessage(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("messageId") Long messageId
    ) {
        MessageResponse response = messageService.deleteMessage(messageId, currentUser);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }

    @PutMapping("/{id}/permissions")
    public ConversationResponse updatePermissions(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        boolean onlyAdminsCanPost = Boolean.TRUE.equals(body.get("onlyAdminsCanPost"));
        boolean isAnnouncement = Boolean.TRUE.equals(body.get("isAnnouncement"));
        return conversationService.updatePermissions(conversationId, currentUser.getId(), onlyAdminsCanPost, isAnnouncement);
    }

    @PostMapping("/{id}/mute")
    public ConversationResponse mute(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Integer> body
    ) {
        Integer durationHours = body.get("durationHours");
        return conversationService.muteConversation(conversationId, currentUser.getId(), durationHours);
    }

    @PostMapping("/{id}/messages/{messageId}/pin")
    public MessageResponse togglePin(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("messageId") Long messageId
    ) {
        MessageResponse response = messageService.togglePin(messageId, currentUser);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }
}