package com.officechat.office_chat_backend.message.dto;

// threadRootId is set when typing in a thread reply box (null = main chat)
public record TypingEvent(Long conversationId, Long userId, String userName, boolean typing, Long threadRootId) {}