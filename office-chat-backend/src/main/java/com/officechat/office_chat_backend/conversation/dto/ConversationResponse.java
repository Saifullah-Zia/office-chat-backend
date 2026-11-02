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
        boolean archived,         // archived in my chat list
        String description,
        boolean isPublic,         // listed in the channel browser
        Integer retentionDays,    // disappearing messages (null = off)
        String inviteCode,        // only sent to group admins
        List<String> labels,      // work-tracking labels (not shown to providers)
        String ticketStatus,      // null, "OPEN" or "DONE"
        String ticketUpdatedByName,
        Instant ticketUpdatedAt
) {}