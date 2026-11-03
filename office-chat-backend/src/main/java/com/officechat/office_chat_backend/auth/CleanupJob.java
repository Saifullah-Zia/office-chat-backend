package com.officechat.office_chat_backend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Once a day, deletes sign-in records nobody needs any more, so the database doesn't grow forever.
 * Audit logs are deliberately kept (compliance).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupJob {

    /** Old sessions are kept a while: they're how a returning device is recognised (no "new device" email). */
    private static final Duration KEEP_EXPIRED_SESSIONS = Duration.ofDays(90);
    private static final Duration KEEP_RESET_LINKS = Duration.ofDays(7);

    private final UserSessionRepository sessionRepository;
    private final PasswordResetTokenRepository resetTokenRepository;

    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void run() {
        try {
            Instant now = Instant.now();
            int sessions = sessionRepository.deleteExpiredBefore(now.minus(KEEP_EXPIRED_SESSIONS));
            int links = resetTokenRepository.deleteExpiredBefore(now.minus(KEEP_RESET_LINKS));
            if (sessions + links > 0) log.info("Cleanup: removed {} old sessions and {} old reset links", sessions, links);
        } catch (Exception e) {
            log.warn("Cleanup failed: {}", e.getMessage());
        }
    }
}
