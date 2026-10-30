package com.officechat.office_chat_backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByInviteCode(String inviteCode);

    List<Conversation> findByTypeAndPublicChannelTrueOrderByNameAsc(ConversationType type);

    List<Conversation> findByRetentionDaysGreaterThan(Integer days);
}
