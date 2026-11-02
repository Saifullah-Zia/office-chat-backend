package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.settings.SettingsService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Screen lock after inactivity (shared office PCs): the company-wide timeout, and the
 * password check that unlocks it.
 */
@RestController
@RequiredArgsConstructor
public class IdleLockController {

    public static final String KEY = "security.idleLockMinutes";
    private static final Set<Integer> ALLOWED = Set.of(0, 5, 10, 15, 30, 60, 120);
    private static final int DEFAULT_MINUTES = 30;

    private final SettingsService settings;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;

    /** Everyone reads it (the app needs it to know when to lock). 0 = off. */
    @GetMapping("/api/policies/idle-lock")
    public Map<String, Integer> get() {
        return Map.of("minutes", minutes());
    }

    /** Body: { minutes } */
    @PutMapping("/api/admin/idle-lock")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> set(@AuthenticationPrincipal User admin, @RequestBody Map<String, Integer> body) {
        Integer m = body.get("minutes");
        if (m == null || !ALLOWED.contains(m)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose one of: off, 5, 10, 15, 30, 60 or 120 minutes");
        }
        settings.set(KEY, String.valueOf(m));
        auditService.log(admin.getId(), "IDLE_LOCK_CHANGED", "SETTING", null,
                "Screen lock after inactivity: " + (m == 0 ? "off" : m + " minutes"));
        return Map.of("minutes", m);
    }

    /** Body: { password }. Wrong answers are limited so a locked screen can't be guessed open. */
    @PostMapping("/api/account/unlock")
    public void unlock(@AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        String key = "unlock:" + user.getId();
        if (!rateLimiter.tryAcquire(key, 5, Duration.ofMinutes(15))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong passwords. Please sign in again.");
        }
        String password = body.get("password");
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            // 400, not 401: a 401 would make the app sign out straight away.
            throw new ApiException(HttpStatus.BAD_REQUEST, "That password isn't right.");
        }
        rateLimiter.reset(key);
    }

    private int minutes() {
        try {
            return Integer.parseInt(settings.get(KEY, String.valueOf(DEFAULT_MINUTES)));
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }
}
