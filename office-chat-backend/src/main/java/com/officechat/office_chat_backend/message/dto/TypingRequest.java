package com.officechat.office_chat_backend.message.dto;

import jakarta.validation.constraints.NotNull;

public record TypingRequest(@NotNull Long conversationId, boolean typing) {}