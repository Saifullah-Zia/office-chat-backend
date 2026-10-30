package com.officechat.office_chat_backend.schedule;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "scheduled_messages", indexes = @Index(name = "idx_sched_due", columnList = "status, send_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledMessage {

    public enum Status { PENDING, SENT, CANCELLED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "send_at", nullable = false)
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "sent_message_id")
    private Long sentMessageId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (status == null) status = Status.PENDING;
    }
}
