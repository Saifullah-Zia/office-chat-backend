package com.officechat.office_chat_backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByAssigneeIdOrderByCreatedAtDesc(Long assigneeId);

    List<Task> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    List<Task> findByConversationIdOrderByCreatedAtDesc(Long conversationId);

    long countByAssigneeIdAndStatus(Long assigneeId, Task.Status status);
}
