package com.officechat.office_chat_backend.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, Long> {

    List<ScheduledMessage> findByStatusAndSendAtLessThanEqualOrderBySendAtAsc(ScheduledMessage.Status status, Instant now);

    List<ScheduledMessage> findBySenderIdAndStatusOrderBySendAtAsc(Long senderId, ScheduledMessage.Status status);

    List<ScheduledMessage> findBySenderIdAndConversationIdAndStatusOrderBySendAtAsc(
            Long senderId, Long conversationId, ScheduledMessage.Status status);

    long countBySenderIdAndStatus(Long senderId, ScheduledMessage.Status status);
}
