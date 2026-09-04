package com.officechat.office_chat_backend.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByMessageId(Long messageId);

    @Query("select coalesce(sum(a.fileSize), 0) from Attachment a")
    long sumAllFileSizes();
}