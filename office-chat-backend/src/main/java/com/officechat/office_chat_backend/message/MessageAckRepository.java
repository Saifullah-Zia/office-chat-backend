package com.officechat.office_chat_backend.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageAckRepository extends JpaRepository<MessageAck, Long> {
    boolean existsByMessageIdAndUserId(Long messageId, Long userId);
    long countByMessageId(Long messageId);
    List<MessageAck> findByMessageIdOrderByAckedAtAsc(Long messageId);

    /** [messageId, count] for every acknowledged message in a conversation. */
    @Query("select a.messageId, count(a) from MessageAck a where a.conversationId = :conversationId group by a.messageId")
    List<Object[]> countsByConversation(@Param("conversationId") Long conversationId);

    @Query("select a.messageId from MessageAck a where a.conversationId = :conversationId and a.userId = :userId")
    List<Long> myAckedMessageIds(@Param("conversationId") Long conversationId, @Param("userId") Long userId);
}
