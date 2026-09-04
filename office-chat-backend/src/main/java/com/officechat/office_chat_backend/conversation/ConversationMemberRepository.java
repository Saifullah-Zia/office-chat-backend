package com.officechat.office_chat_backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    List<ConversationMember> findByUserId(Long userId);

    List<ConversationMember> findByConversationId(Long conversationId);

    Optional<ConversationMember> findByConversationIdAndUserId(Long conversationId, Long userId);

    boolean existsByConversationIdAndUserId(Long conversationId, Long userId);

    @Query("select cm.conversation.id from ConversationMember cm " +
            "where cm.user.id = :userId1 and cm.conversation.type = 'DIRECT' " +
            "and cm.conversation.id in (select cm2.conversation.id from ConversationMember cm2 where cm2.user.id = :userId2)")
    List<Long> findDirectConversationIdsBetween(@Param("userId1") Long userId1, @Param("userId2") Long userId2);
}