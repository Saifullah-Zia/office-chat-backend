package com.officechat.office_chat_backend.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageEditRepository extends JpaRepository<MessageEdit, Long> {
    List<MessageEdit> findByMessageIdOrderByEditedAtAsc(Long messageId);
}
