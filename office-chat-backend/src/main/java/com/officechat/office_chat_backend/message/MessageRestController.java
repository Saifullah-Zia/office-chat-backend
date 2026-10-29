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
