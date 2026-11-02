package com.officechat.office_chat_backend.message.dto;


import com.officechat.office_chat_backend.message.MessageType;
import jakarta.validation.constraints.NotNull;

public record MessageRequest(
        @NotNull Long conversationId,
        String content,        // no longer @NotBlank — a file message can have empty text
        MessageType type,      // optional, inferred if null
        Long attachmentId,      // optional: set when this message carries a file
        Boolean dlpConfirmed    // "send anyway" after a data-loss-prevention warning
) {}