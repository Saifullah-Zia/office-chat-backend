package com.officechat.office_chat_backend.push;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A browser's Web Push subscription for one user (one row per browser/device). */
@Entity
@Table(name = "push_subscriptions", indexes = @Index(name = "idx_push_sub_user", columnList = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 1024)
    private String endpoint;

    /** Browser's P-256 public key, base64url (uncompressed point) */
    @Column(nullable = false, length = 128)
    private String p256dh;

    /** Browser's auth secret, base64url */
    @Column(nullable = false, length = 64)
    private String auth;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
