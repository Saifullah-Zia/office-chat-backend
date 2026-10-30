package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.conversation.dto.ConversationResponse;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Joining groups without being added: invite links and public channels. */
@RestController
@RequiredArgsConstructor
public class JoinController {

    private final ConversationService conversationService;
    private final RateLimiter rateLimiter;

    private void limit(User user) {
        rateLimiter.check("join:" + user.getId(), 30, Duration.ofMinutes(5), "Too many attempts. Please wait a few minutes.");
    }

    @GetMapping("/api/invites/{code}")
    public Map<String, Object> previewInvite(@AuthenticationPrincipal User currentUser, @PathVariable String code) {
        limit(currentUser);
        return conversationService.previewInvite(code, currentUser.getId());
    }

    @PostMapping("/api/invites/{code}/join")
    public ConversationResponse joinByInvite(@AuthenticationPrincipal User currentUser, @PathVariable String code) {
        limit(currentUser);
        return conversationService.joinByInvite(code, currentUser.getId());
    }

    @GetMapping("/api/channels")
    public List<Map<String, Object>> channels(@AuthenticationPrincipal User currentUser, @RequestParam(required = false) String q) {
        return conversationService.listPublicChannels(currentUser.getId(), q);
    }

    @PostMapping("/api/channels/{id}/join")
    public ConversationResponse joinChannel(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        return conversationService.joinPublicChannel(id, currentUser.getId());
    }
}
