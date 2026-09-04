package com.officechat.office_chat_backend.poll.dto;

import java.util.List;

public record CreatePollRequest(
        String question,
        boolean multipleAnswers,
        List<String> options
) {
}
