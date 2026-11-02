package com.officechat.office_chat_backend.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {
    /** Mine plus the shared ones. */
    @Query("select t from MessageTemplate t where t.ownerId = :userId or t.ownerId is null order by t.shortcut")
    List<MessageTemplate> findVisibleTo(@Param("userId") Long userId);

    long countByOwnerId(Long ownerId);
}
