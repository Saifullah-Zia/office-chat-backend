package com.officechat.office_chat_backend.call;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface CallRepository extends JpaRepository<Call, Long> {
    List<Call> findByConversationIdOrderByStartedAtDesc(Long conversationId);

    List<Call> findByConversationIdInOrderByStartedAtDesc(Collection<Long> conversationIds, Pageable pageable);
}