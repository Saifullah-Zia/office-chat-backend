package com.officechat.office_chat_backend.push;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One installed Android app (its Firebase token), so we can wake that phone for calls and messages. */
@Entity
@Table(name = "native_push_tokens", indexes = @Index(name = "idx_native_push_user", columnList = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NativePushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** "android" for now. */
    @Column(nullable = false, length = 20)
    private String platform;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
