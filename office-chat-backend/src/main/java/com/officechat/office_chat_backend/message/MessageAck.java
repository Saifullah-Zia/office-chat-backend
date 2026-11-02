package com.officechat.office_chat_backend.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** "Acknowledged" on an announcement-group message. One per person per message; can't be undone. */
@Entity
@Table(name = "message_acks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_id"}),
        indexes = @Index(name = "idx_message_acks_conv", columnList = "conversation_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "acked_at", nullable = false)
    private Instant ackedAt;
}
