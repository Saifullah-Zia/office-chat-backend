package com.officechat.office_chat_backend.message.dto;

public record TypingEvent(Long conversationId, Long userId, String userName, boolean typing) {}