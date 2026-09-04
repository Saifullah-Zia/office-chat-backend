package com.officechat.office_chat_backend.admin.dto;

public record AdminStatsResponse(
        long totalUsers,
        long activeUsers,
        long totalConversations,
        long totalMessages,
        long totalFiles,
        long totalStorageBytes
) {}