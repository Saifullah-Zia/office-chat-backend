package com.officechat.office_chat_backend.conversation.dto;

import com.officechat.office_chat_backend.conversation.ConversationType;
import com.officechat.office_chat_backend.user.UserResponse;
import java.time.Instant;
import java.util.List;

public record ConversationResponse(
        Long id,
        ConversationType type,
        String name,
        List<UserResponse> members,
        String lastMessagePreview,
        Instant lastMessageAt,
        long unreadCount,
        boolean onlyAdminsCanPost,
        boolean isAnnouncement,
        Instant mutedUntil
) {}