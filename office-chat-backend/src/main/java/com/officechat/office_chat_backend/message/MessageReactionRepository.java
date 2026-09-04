package com.officechat.office_chat_backend.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {
    List<MessageReaction> findByMessageId(Long messageId);
    List<MessageReaction> findByMessageIdIn(List<Long> messageIds);
    Optional<MessageReaction> findByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);
    void deleteByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);
}
