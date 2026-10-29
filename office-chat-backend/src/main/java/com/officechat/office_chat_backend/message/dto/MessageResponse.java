package com.officechat.office_chat_backend.message.dto;

import com.officechat.office_chat_backend.file.dto.AttachmentResponse;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageType;
import com.officechat.office_chat_backend.poll.dto.PollResponse;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record MessageResponse(
        Long id,
        Long conversationId,
        Long senderId,
        String senderName,
        String content,
        MessageType type,
        Instant createdAt,
        Instant editedAt,
        boolean edited,
        boolean deleted,
        List<AttachmentResponse> attachments,
        Map<String, Integer> reactions,
        List<ReactionDetailResponse> reactionDetails,
        PollResponse poll,
        boolean read,
        boolean delivered,
        boolean pinned,
        boolean forwarded
) {
    public static MessageResponse from(Message message, List<AttachmentResponse> attachments) {
        return from(message, attachments, Collections.emptyMap(), Collections.emptyList(), null, false, false);
    }

    public static MessageResponse from(Message message, List<AttachmentResponse> attachments, boolean read, boolean delivered) {
        return from(message, attachments, Collections.emptyMap(), Collections.emptyList(), null, read, delivered);
    }

    public static MessageResponse from(
            Message message,
            List<AttachmentResponse> attachments,
            Map<String, Integer> reactions,
            List<ReactionDetailResponse> reactionDetails,
            PollResponse poll,
            boolean read,
            boolean delivered
    ) {
        boolean isDeleted = message.getDeletedAt() != null;
        boolean isEdited = message.getEditedAt() != null && !isDeleted;
        String displayContent = isDeleted ? "🚫 This message was deleted" : message.getContent();

        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getSender().getId(),
                message.getSender().getName(),
                displayContent,
                message.getType(),
                message.getCreatedAt(),
                message.getEditedAt(),
                isEdited,
                isDeleted,
                isDeleted ? Collections.emptyList() : attachments,
                reactions != null ? reactions : Collections.emptyMap(),
                reactionDetails != null ? reactionDetails : Collections.emptyList(),
                poll,
                read,
                delivered,
                message.isPinned(),
                message.isForwardedMessage()
        );
    }
}