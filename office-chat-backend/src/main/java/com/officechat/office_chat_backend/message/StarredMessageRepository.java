package com.officechat.office_chat_backend.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StarredMessageRepository extends JpaRepository<StarredMessage, Long> {

    List<StarredMessage> findByUserIdOrderByStarredAtDesc(Long userId);

    Optional<StarredMessage> findByUserIdAndMessageId(Long userId, Long messageId);

    boolean existsByUserIdAndMessageId(Long userId, Long messageId);
}
