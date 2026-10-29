package com.officechat.office_chat_backend.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "starred_messages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "message_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StarredMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "starred_at", updatable = false)
    private Instant starredAt;

    @PrePersist
    protected void onCreate() {
        this.starredAt = Instant.now();
    }
}
