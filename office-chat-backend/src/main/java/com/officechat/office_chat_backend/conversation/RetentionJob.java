package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.file.Attachment;
import com.officechat.office_chat_backend.file.AttachmentRepository;
import com.officechat.office_chat_backend.file.FileStorageService;
import com.officechat.office_chat_backend.message.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Disappearing messages. Members already can't see expired messages (history is filtered by
 * {@link ConversationService#getVisibleSince}); this job permanently wipes their text and files.
 */
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final FileStorageService storageService;

    @Value("${app.retention.max-days:0}")
    private int orgMaxRetentionDays;

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void purgeExpired() {
        List<Conversation> targets = orgMaxRetentionDays > 0
                ? conversationRepository.findAll()
                : conversationRepository.findByRetentionDaysGreaterThan(0);
        Instant now = Instant.now();

        for (Conversation c : targets) {
            int days = conversationService.effectiveRetentionDays(c.getRetentionDays());
            if (days <= 0) continue;
            Instant cutoff = now.minus(Duration.ofDays(days));

            for (Attachment a : attachmentRepository.findExpired(c.getId(), cutoff)) {
                String stored = a.getStoredFileName();
                attachmentRepository.delete(a);
                attachmentRepository.flush();
                if (attachmentRepository.countByStoredFileName(stored) == 0) storageService.delete(stored);
            }
            int wiped = messageRepository.scrubOlderThan(c.getId(), cutoff, now);
            if (wiped > 0) System.out.println("Retention: wiped " + wiped + " message(s) in conversation " + c.getId());
        }
    }
}
