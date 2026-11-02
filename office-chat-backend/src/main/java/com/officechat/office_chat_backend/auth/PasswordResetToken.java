package com.officechat.office_chat_backend.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A "forgot password" link. Only a SHA-256 hash of the link's secret is stored. Single use. */
@Entity
@Table(name = "password_reset_tokens", indexes = @Index(name = "idx_pwreset_hash", columnList = "token_hash", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
