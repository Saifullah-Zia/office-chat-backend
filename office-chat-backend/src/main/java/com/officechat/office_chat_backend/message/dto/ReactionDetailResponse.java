package com.officechat.office_chat_backend.message.dto;

public record ReactionDetailResponse(
        Long userId,
        String userName,
        String emoji
) {
}
