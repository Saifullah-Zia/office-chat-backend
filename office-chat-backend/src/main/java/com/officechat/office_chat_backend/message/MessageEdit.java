package com.officechat.office_chat_backend.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The text a message had before an edit (one row per edit), for "edit history". */
@Entity
@Table(name = "message_edits", indexes = @Index(name = "idx_message_edits_message", columnList = "message_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "previous_content", columnDefinition = "TEXT", nullable = false)
    private String previousContent;

    // When this version was replaced
    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;
}
