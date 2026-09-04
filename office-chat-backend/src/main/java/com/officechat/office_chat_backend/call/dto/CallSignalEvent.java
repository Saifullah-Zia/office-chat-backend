package com.officechat.office_chat_backend.call.dto;

public record CallSignalEvent(
        Long callId,
        Long conversationId,
        Long fromUserId,
        String fromUserName,
        String type,
        Object payload
) {}