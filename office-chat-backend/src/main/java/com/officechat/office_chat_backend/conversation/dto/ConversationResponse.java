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
        Instant mutedUntil,
        List<Long> adminIds,      // per-group admins (members with is_admin)
        boolean blockedByMe,      // DIRECT only: I blocked the other member
        boolean blockedMe,        // DIRECT only: the other member blocked me
        boolean pinned,           // pinned in my chat list
        boolean archived          // archived in my chat list
) {}