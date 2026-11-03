package com.officechat.office_chat_backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastActiveAtDesc(Long userId, Instant now);

    boolean existsByUserIdAndDeviceHash(Long userId, String deviceHash);

    boolean existsByUserIdAndDeviceHashIsNotNull(Long userId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from UserSession s where s.expiresAt < :before")
    int deleteExpiredBefore(@org.springframework.data.repository.query.Param("before") Instant before);
}
