package com.officechat.office_chat_backend.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUserIdIn(Collection<Long> userIds);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    long countByUserId(Long userId);
}
