package com.officechat.office_chat_backend.call.dto;

import com.officechat.office_chat_backend.call.CallType;
import jakarta.validation.constraints.NotNull;

public record InitiateCallRequest(@NotNull Long conversationId, @NotNull CallType type) {}