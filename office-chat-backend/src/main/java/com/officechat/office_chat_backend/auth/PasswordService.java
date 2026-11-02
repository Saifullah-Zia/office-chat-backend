package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/** Password changes, admin resets and temporary passwords. */
@Service
@RequiredArgsConstructor
public class PasswordService {

    public static final int MIN_LENGTH = 8;
    // No look-alikes (0/O, 1/l/I) so a temporary password can be read out or typed easily.
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final AuditService auditService;

    public static String generateTemporary() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(TEMP_CHARS.charAt(RANDOM.nextInt(TEMP_CHARS.length())));
        return sb.toString();
    }

    /**
     * Admin: give someone a new temporary password. They're signed out everywhere and must pick their own
     * password at next login. Optionally turns off two-step login (lost phone). Returns the password, shown once.
     */
    @Transactional
    public String adminReset(User admin, Long userId, boolean disableTwoFactor) {
        if (admin.getId().equals(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use Settings → Security to change your own password");
        }
        User u = userRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        String temp = generateTemporary();
        u.setPassword(passwordEncoder.encode(temp));
        u.setMustChangePassword(true);
        if (disableTwoFactor) {
            u.setTotpEnabled(false);
            u.setTotpSecret(null);
            u.setTotpPendingSecret(null);
            u.setTotpLastStep(null);
            u.setRecoveryCodes(null);
        }
        userRepository.save(u);
        sessionService.revokeAllExcept(userId, null);
        auditService.log(admin.getId(), "PASSWORD_RESET_BY_ADMIN", "USER", userId,
                "Temporary password issued for " + u.getEmail() + (disableTwoFactor ? "; two-step login turned off" : ""));
        return temp;
    }

    /** Anyone: change my password (also completes a forced change). Other devices are signed out. */
    @Transactional
    public void change(User user, String currentSessionId, String currentPassword, String newPassword) {
        User u = userRepository.findById(user.getId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, u.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Your current password isn't right");
        }
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use at least " + MIN_LENGTH + " characters");
        }
        if (newPassword.length() > 128) throw new ApiException(HttpStatus.BAD_REQUEST, "That password is too long");
        if (passwordEncoder.matches(newPassword, u.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a password different from the current one");
        }
        u.setPassword(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(false);
        userRepository.save(u);
        sessionService.revokeAllExcept(u.getId(), currentSessionId);
        auditService.log(u.getId(), "PASSWORD_CHANGED", "USER", u.getId(), "Changed their password");
    }
}
