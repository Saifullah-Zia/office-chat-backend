package com.officechat.office_chat_backend.call.dto;

import jakarta.validation.constraints.NotNull;

public record CallSignalRequest(
        @NotNull Long callId,
        @NotNull Long conversationId,
        Long toUserId,      // set for a direct 1:1 signal; null = broadcast to whole conversation (group ringing)
        @NotNull String type, // "OFFER" | "ANSWER" | "ICE_CANDIDATE" | "HANGUP" | "BUSY"
        Object payload      // SDP string or ICE candidate object — backend never inspects this, just relays it
) {}