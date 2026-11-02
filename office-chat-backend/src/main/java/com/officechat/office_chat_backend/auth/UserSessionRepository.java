package com.officechat.office_chat_backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastActiveAtDesc(Long userId, Instant now);

    boolean existsByUserIdAndDeviceHash(Long userId, String deviceHash);

    boolean existsByUserIdAndDeviceHashIsNotNull(Long userId);
}
