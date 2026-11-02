package com.officechat.office_chat_backend.template;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A saved reply, inserted in the composer by typing "/shortcut". ownerId null = shared with everyone. */
@Entity
@Table(name = "message_templates", indexes = @Index(name = "idx_templates_owner", columnList = "owner_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 30)
    private String shortcut;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
