package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageRestController {

    private final MessageService messageService;
    private final MessageBroadcaster broadcaster;

    @GetMapping("/starred")
    public List<MessageResponse> getStarred(@AuthenticationPrincipal User currentUser) {
        return messageService.getStarred(currentUser.getId());
    }

    /** Server-side search across my chats. has = link | image | file | voice; from/to are ISO-8601 instants. */
    @GetMapping("/search")
    public List<MessageResponse> search(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) String has,
            @RequestParam(defaultValue = "40") int limit
    ) {
        return messageService.search(currentUser.getId(), q, conversationId, senderId, from, to, has, limit);
    }

    /** @mentions of me, @all/@here in my groups, and replies to me — newest first. */
    @GetMapping("/mentions")
    public List<MessageResponse> mentions(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return messageService.mentions(currentUser, limit);
    }

    @PostMapping("/{messageId}/star")
    public MessageResponse star(@AuthenticationPrincipal User currentUser, @PathVariable Long messageId) {
        return messageService.star(messageId, currentUser.getId());
    }

    @DeleteMapping("/{messageId}/star")
    public void unstar(@AuthenticationPrincipal User currentUser, @PathVariable Long messageId) {
        messageService.unstar(messageId, currentUser.getId());
    }

    /** Body: { "conversationIds": [1, 2, 3] } */
    @PostMapping("/{messageId}/forward")
    public List<MessageResponse> forward(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long messageId,
            @RequestBody Map<String, List<Long>> body
    ) {
        List<MessageResponse> sent = messageService.forward(messageId, currentUser, body.get("conversationIds"));
        sent.forEach(broadcaster::broadcastNew);
        return sent;
    }
}
