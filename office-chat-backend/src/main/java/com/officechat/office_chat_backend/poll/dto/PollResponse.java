package com.officechat.office_chat_backend.poll.dto;

import java.util.List;

public record PollResponse(
        Long id,
        Long messageId,
        String question,
        boolean multipleAnswers,
        int totalVotes,
        List<PollOptionResponse> options,
        java.time.Instant closesAt,
        boolean closed
) {
}
