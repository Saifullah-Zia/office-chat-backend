package com.officechat.office_chat_backend.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One signed-in device/browser. The JWT carries its id ("sid") so it can be signed out remotely. */
@Entity
@Table(name = "user_sessions", indexes = @Index(name = "idx_session_user", columnList = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** SHA-256 of the browser's random device id (X-Device-Id), used to spot sign-ins from new devices. */
    @Column(name = "device_hash", length = 64)
    private String deviceHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
