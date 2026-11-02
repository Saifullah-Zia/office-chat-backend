package com.officechat.office_chat_backend.file;

import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.file.dto.AttachmentResponse;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The chat's "Files" tab: everything ever shared, not just the messages loaded on screen. */
@RestController
@RequiredArgsConstructor
public class ConversationFilesController {

    private final AttachmentRepository attachmentRepository;
    private final ConversationService conversationService;

    public record SharedFile(AttachmentResponse file, Long messageId, Long senderId, String senderName, java.time.Instant sharedAt) {}

    @GetMapping("/api/conversations/{id}/files")
    @Transactional(readOnly = true)
    public List<SharedFile> files(@AuthenticationPrincipal User user, @PathVariable("id") Long conversationId) {
        conversationService.assertMember(conversationId, user.getId());
        // Respects "clear chat" and disappearing messages, like the timeline does.
        return attachmentRepository.findSharedInConversation(conversationId,
                        conversationService.getVisibleSince(conversationId, user.getId())).stream()
                .map(a -> new SharedFile(AttachmentResponse.from(a), a.getMessage().getId(),
                        a.getMessage().getSender().getId(), a.getMessage().getSender().getName(), a.getMessage().getCreatedAt()))
                .toList();
    }
}
