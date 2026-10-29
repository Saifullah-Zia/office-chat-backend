package com.officechat.office_chat_backend.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    /** Cursor page: messages older than {@code beforeId} and newer than {@code after} (the member's clear point). */
    Page<Message> findByConversationIdAndIdLessThanAndCreatedAtAfterOrderByIdDesc(
            Long conversationId, Long beforeId, Instant after, Pageable pageable);

    Optional<Message> findTopByConversationIdOrderByCreatedAtDesc(Long conversationId);

    long countByConversationIdAndCreatedAtAfter(Long conversationId, Instant after);
}