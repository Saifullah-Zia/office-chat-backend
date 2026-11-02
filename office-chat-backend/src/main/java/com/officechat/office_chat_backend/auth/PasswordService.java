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
    private final PasswordResetTokenRepository resetTokenRepository;
    private final com.officechat.office_chat_backend.mail.EmailService emailService;

    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private static final java.time.Duration RESET_LINK_TTL = java.time.Duration.ofMinutes(30);

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

    // ── Forgot password (email link) ─────────────────────────────────────

    /**
     * Emails a one-time reset link if the address belongs to an active account. Callers always get the
     * same answer, so this can't be used to find out who has an account.
     */
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) return;
        userRepository.findByEmailIgnoreCase(email.trim()).filter(User::isActive).ifPresent(u -> {
            byte[] secret = new byte[32];
            RANDOM.nextBytes(secret);
            String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            java.time.Instant now = java.time.Instant.now();
            resetTokenRepository.save(PasswordResetToken.builder()
                    .userId(u.getId()).tokenHash(sha256(token)).createdAt(now).expiresAt(now.plus(RESET_LINK_TTL)).build());

            String link = frontendUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:480px;margin:auto;color:#0f172a">
                      <h2 style="margin:0 0 12px">Reset your Office Chat password</h2>
                      <p>Hi %s,</p>
                      <p>Someone (hopefully you) asked to reset your password. This link works once and expires in 30 minutes.</p>
                      <p style="margin:24px 0"><a href="%s" style="background:#4f46e5;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none;font-weight:bold">Choose a new password</a></p>
                      <p style="color:#64748b;font-size:13px">If you didn't ask for this, ignore this email; your password stays the same.</p>
                    </div>""".formatted(escape(u.getName()), link);
            String text = "Hi " + u.getName() + ",\n\nReset your Office Chat password (the link works once and expires in 30 minutes):\n"
                    + link + "\n\nIf you didn't ask for this, ignore this email.";
            emailService.send(u.getEmail(), "Reset your Office Chat password", html, text);
            auditService.log(u.getId(), "PASSWORD_RESET_REQUESTED", "USER", u.getId(), "Reset link emailed");
        });
    }

    /** Uses a reset link: sets the new password, ends every session, and kills the link. */
    @Transactional
    public void completeReset(String token, String newPassword) {
        PasswordResetToken t = token == null ? null : resetTokenRepository.findByTokenHash(sha256(token)).orElse(null);
        java.time.Instant now = java.time.Instant.now();
        if (t == null || t.getUsedAt() != null || t.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This link has expired or was already used. Ask for a new one.");
        }
        User u = userRepository.findById(t.getUserId()).filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "This account can't be reset"));
        validateNew(newPassword);
        u.setPassword(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(false);
        userRepository.save(u);
        resetTokenRepository.invalidateAll(u.getId(), now);
        sessionService.revokeAllExcept(u.getId(), null);
        auditService.log(u.getId(), "PASSWORD_RESET_BY_EMAIL", "USER", u.getId(), "Password reset with an emailed link");
    }

    private static void validateNew(String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use at least " + MIN_LENGTH + " characters");
        }
        if (newPassword.length() > 128) throw new ApiException(HttpStatus.BAD_REQUEST, "That password is too long");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Anyone: change my password (also completes a forced change). Other devices are signed out. */
    @Transactional
    public void change(User user, String currentSessionId, String currentPassword, String newPassword) {
        User u = userRepository.findById(user.getId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, u.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Your current password isn't right");
        }
        validateNew(newPassword);
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
