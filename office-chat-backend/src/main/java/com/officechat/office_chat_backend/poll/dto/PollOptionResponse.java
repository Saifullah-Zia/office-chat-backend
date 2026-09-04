package com.officechat.office_chat_backend.poll.dto;

import java.util.List;

public record PollOptionResponse(
        Long id,
        String optionText,
        int voteCount,
        boolean votedByMe,
        List<String> voterNames
) {
}
