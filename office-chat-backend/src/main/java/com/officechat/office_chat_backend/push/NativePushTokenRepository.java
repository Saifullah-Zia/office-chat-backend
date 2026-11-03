package com.officechat.office_chat_backend.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NativePushTokenRepository extends JpaRepository<NativePushToken, Long> {

    Optional<NativePushToken> findByToken(String token);

    List<NativePushToken> findByUserIdIn(Collection<Long> userIds);

    long countByUserId(Long userId);
}
