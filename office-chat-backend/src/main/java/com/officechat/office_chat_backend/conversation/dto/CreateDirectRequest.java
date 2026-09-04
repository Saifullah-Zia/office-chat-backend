package com.officechat.office_chat_backend.conversation.dto;


import jakarta.validation.constraints.NotNull;

public record CreateDirectRequest(@NotNull Long otherUserId) {}