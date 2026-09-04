package com.officechat.office_chat_backend.call.dto;

import com.officechat.office_chat_backend.call.Call;
import com.officechat.office_chat_backend.call.CallStatus;
import com.officechat.office_chat_backend.call.CallType;
import java.time.Instant;

public record CallResponse(
        Long id,
        Long conversationId,
        Long initiatorId,
        String initiatorName,
        CallType type,
        CallStatus status,
        Instant startedAt,
        Instant answeredAt,
        Instant endedAt
) {
    public static CallResponse from(Call call) {
        return new CallResponse(
                call.getId(),
                call.getConversation().getId(),
                call.getInitiator().getId(),
                call.getInitiator().getName(),
                call.getType(),
                call.getStatus(),
                call.getStartedAt(),
                call.getAnsweredAt(),
                call.getEndedAt()
        );
    }
}