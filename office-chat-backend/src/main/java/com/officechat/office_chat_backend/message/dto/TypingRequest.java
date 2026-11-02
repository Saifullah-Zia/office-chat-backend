package com.officechat.office_chat_backend.message.dto;

import jakarta.validation.constraints.NotNull;

// userId/userName are ignored by the server (it uses the signed-in user); kept for older clients.
public record TypingRequest(@NotNull Long conversationId, Long userId, String userName, boolean typing, Long threadRootId) {}