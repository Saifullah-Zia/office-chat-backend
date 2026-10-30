package com.officechat.office_chat_backend.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_assignee", columnList = "assignee_id, status"),
        @Index(name = "idx_task_creator", columnList = "created_by")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    public enum Status { OPEN, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Optional: the chat the task belongs to (assignee must be a member). */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** Optional: the message the task was created from. */
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (status == null) status = Status.OPEN;
    }
}
