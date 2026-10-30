package com.officechat.office_chat_backend.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByMessageId(Long messageId);

    @Query("select coalesce(sum(a.fileSize), 0) from Attachment a")
    long sumAllFileSizes();

    /** Attachments on messages older than the cutoff in a conversation (for disappearing messages). */
    @Query("select a from Attachment a where a.message.conversation.id = :conversationId and a.message.createdAt < :cutoff")
    List<Attachment> findExpired(@Param("conversationId") Long conversationId, @Param("cutoff") Instant cutoff);

    /** Forwarded copies share the stored file, so only delete it from disk when nothing else uses it. */
    long countByStoredFileName(String storedFileName);
}
