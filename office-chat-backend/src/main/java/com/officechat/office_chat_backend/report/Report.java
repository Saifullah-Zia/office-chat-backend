package com.officechat.office_chat_backend.report;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "reports", indexes = @Index(name = "idx_report_status", columnList = "status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {

    public enum Status { OPEN, RESOLVED, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    /** The user being reported (always set; for a message report it's the sender). */
    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "conversation_id")
    private Long conversationId;

    /** Snapshot of the reported message at report time, in case it's edited or deleted later. */
    @Column(name = "message_snapshot", columnDefinition = "TEXT")
    private String messageSnapshot;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) this.status = Status.OPEN;
    }
}
