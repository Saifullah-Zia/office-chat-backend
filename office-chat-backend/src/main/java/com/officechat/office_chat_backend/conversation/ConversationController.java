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
    private final com.officechat.office_chat_backend.dlp.DlpService dlpService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.officechat.office_chat_backend.message.MessageBroadcaster broadcaster;

    // ── Threads ──────────────────────────────────────────────────────────

    /** Root message first, then replies oldest → newest. */
    @GetMapping("/{id}/messages/{rootId}/thread")
    public List<MessageResponse> getThread(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("rootId") Long rootId
    ) {
        return messageService.getThread(conversationId, rootId, currentUser.getId());
    }

    /** Body: { content, attachmentId? } — posts a reply and re-broadcasts the root with its new reply count. */
    @PostMapping("/{id}/messages/{rootId}/thread")
    public MessageResponse replyInThread(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("rootId") Long rootId,
            @RequestBody com.officechat.office_chat_backend.message.dto.MessageRequest request
    ) {
        dlpService.warnUnlessConfirmed(request.content(), request.dlpConfirmed(), currentUser.getId(), conversationId);
        MessageResponse reply = messageService.send(conversationId, currentUser, request.content(),
                request.type(), request.attachmentId(), rootId);
        broadcaster.broadcastNew(reply);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId,
                messageService.getMessageView(rootId, currentUser.getId()));
        return reply;
    }

    // ── Group details, disappearing messages, invite links ──────────────

    /** Body (all optional): { name, description, isPublic } — group admins only. */
    @PutMapping("/{id}/details")
    public ConversationResponse updateDetails(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Object> body
    ) {
        Object pub = body.get("isPublic");
        return conversationService.updateDetails(conversationId, currentUser.getId(),
                (String) body.get("name"), (String) body.get("description"),
                pub == null ? null : Boolean.valueOf(pub.toString()));
    }

    /** Body: { days: 0 | 1 | 7 | 30 | 90 } */
    @PutMapping("/{id}/retention")
    public ConversationResponse setRetention(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Integer> body
    ) {
        return conversationService.setRetention(conversationId, currentUser.getId(), body.get("days"));
    }

    @PostMapping("/{id}/invite")
    public ConversationResponse resetInvite(@AuthenticationPrincipal User currentUser, @PathVariable("id") Long conversationId) {
        return conversationService.resetInviteLink(conversationId, currentUser.getId());
    }

    @DeleteMapping("/{id}/invite")
    public ConversationResponse disableInvite(@AuthenticationPrincipal User currentUser, @PathVariable("id") Long conversationId) {
        return conversationService.disableInviteLink(conversationId, currentUser.getId());
    }

    @PutMapping("/{id}/pin")
    public ConversationResponse pin(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        return conversationService.setPinned(conversationId, currentUser.getId(), Boolean.TRUE.equals(body.get("pinned")));
    }

    @PutMapping("/{id}/archive")
    public ConversationResponse archive(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        return conversationService.setArchived(conversationId, currentUser.getId(), Boolean.TRUE.equals(body.get("archived")));
    }

    @GetMapping("/{id}/messages/{messageId}/receipts")
    public List<java.util.Map<String, Object>> receipts(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("messageId") Long messageId
    ) {
        return conversationService.getReadReceipts(conversationId, messageId, currentUser.getId());
    }

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
        return conversationService.createGroup(currentUser.getId(), request.name(), request.memberIds(),
                request.description(), Boolean.TRUE.equals(request.isPublic()));
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> getMessages(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) Long before
    ) {
        return messageService.getHistory(conversationId, currentUser.getId(), page, Math.min(size, 100), before);
    }

    @PostMapping("/{id}/messages")
    public MessageResponse sendMessage(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody com.officechat.office_chat_backend.message.dto.MessageRequest request
    ) {
        dlpService.warnUnlessConfirmed(request.content(), request.dlpConfirmed(), currentUser.getId(), conversationId);
        MessageResponse response = messageService.send(
                conversationId, currentUser, request.content(), request.type(), request.attachmentId()
        );
        broadcaster.broadcastNew(response);
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

    @PutMapping("/{id}/members/{userId}/admin")
    public ConversationResponse setMemberAdmin(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @PathVariable("userId") Long targetUserId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        boolean admin = Boolean.TRUE.equals(body.get("admin"));
        return conversationService.setMemberAdmin(conversationId, currentUser.getId(), targetUserId, admin);
    }

    @GetMapping("/{id}")
    public ConversationResponse getConversation(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId
    ) {
        return conversationService.getConversation(conversationId, currentUser.getId());
    }

    /** Clear chat history for the current user only. */
    @PostMapping("/{id}/clear")
    public ConversationResponse clearChat(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId
    ) {
        return conversationService.clearChat(conversationId, currentUser.getId());
    }

    /** Delete chat from the current user's list (reappears when a new message arrives). */
    @DeleteMapping("/{id}")
    public void deleteChat(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId
    ) {
        conversationService.deleteChatForMe(conversationId, currentUser.getId());
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