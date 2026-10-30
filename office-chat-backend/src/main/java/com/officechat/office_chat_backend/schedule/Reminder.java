package com.officechat.office_chat_backend.schedule;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "reminders", indexes = @Index(name = "idx_reminder_due", columnList = "status, remind_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reminder {

    /** PENDING → FIRED (delivered or waiting to be seen) → DONE; or CANCELLED. */
    public enum Status { PENDING, FIRED, DONE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(length = 500)
    private String note;

    /** Snapshot of the message text so the reminder still makes sense if it's edited later. */
    @Column(name = "message_snippet", length = 500)
    private String messageSnippet;

    @Column(name = "message_sender")
    private String messageSender;

    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (status == null) status = Status.PENDING;
    }
}
