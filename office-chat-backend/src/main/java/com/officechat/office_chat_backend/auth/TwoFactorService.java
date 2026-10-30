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
import java.util.*;

@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private static final String ISSUER = "Office Chat";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    private User fresh(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public Map<String, Object> status(Long userId) {
        User u = fresh(userId);
        int remaining = u.getRecoveryCodes() == null ? 0
                : (int) Arrays.stream(u.getRecoveryCodes().split("\n")).filter(s -> !s.isBlank()).count();
        return Map.of("enabled", u.isTwoFactorEnabled(), "recoveryCodesLeft", remaining);
    }

    /** Step 1: create a secret for the user to add to their authenticator app. */
    @Transactional
    public Map<String, String> beginSetup(Long userId) {
        User u = fresh(userId);
        if (u.isTwoFactorEnabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "Two-step verification is already on");
        String secret = Totp.newSecret();
        u.setTotpPendingSecret(secret);
        userRepository.save(u);
        return Map.of("secret", secret, "otpauthUrl", Totp.otpauthUrl(ISSUER, u.getEmail(), secret));
    }

    /** Step 2: confirm with a code from the app; returns one-time recovery codes (shown once). */
    @Transactional
    public List<String> confirmSetup(Long userId, String code) {
        User u = fresh(userId);
        if (u.getTotpPendingSecret() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Start setup first");
        long step = Totp.verify(u.getTotpPendingSecret(), code, null);
        if (step < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "That code isn't right. Make sure your phone's clock is correct.");

        List<String> codes = new ArrayList<>();
        StringBuilder hashes = new StringBuilder();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String c = newRecoveryCode();
            codes.add(c);
            hashes.append(passwordEncoder.encode(normalizeRecovery(c))).append('\n');
        }
        u.setTotpSecret(u.getTotpPendingSecret());
        u.setTotpPendingSecret(null);
        u.setTotpEnabled(true);
        u.setTotpLastStep(step);
        u.setRecoveryCodes(hashes.toString());
        userRepository.save(u);
        auditService.log(userId, "2FA_ENABLED", "USER", userId, "Turned on two-step verification");
        return codes;
    }

    /** Turning it off needs the password and a current code (or recovery code). */
    @Transactional
    public void disable(Long userId, String password, String code) {
        User u = fresh(userId);
        if (!u.isTwoFactorEnabled()) return;
        if (password == null || !passwordEncoder.matches(password, u.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Wrong password");
        }
        if (!verifyLoginCode(u, code)) throw new ApiException(HttpStatus.BAD_REQUEST, "That code isn't right");
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        u.setTotpPendingSecret(null);
        u.setTotpLastStep(null);
        u.setRecoveryCodes(null);
        userRepository.save(u);
        auditService.log(userId, "2FA_DISABLED", "USER", userId, "Turned off two-step verification");
    }

    /** Accepts a current authenticator code, or consumes one recovery code. */
    @Transactional
    public boolean verifyLoginCode(User user, String code) {
        User u = fresh(user.getId());
        if (!u.isTwoFactorEnabled() || code == null) return false;

        long step = Totp.verify(u.getTotpSecret(), code, u.getTotpLastStep());
        if (step >= 0) {
            u.setTotpLastStep(step);
            userRepository.save(u);
            return true;
        }

        String candidate = normalizeRecovery(code);
        if (candidate.length() != 10 || u.getRecoveryCodes() == null) return false;
        List<String> hashes = new ArrayList<>(Arrays.stream(u.getRecoveryCodes().split("\n")).filter(s -> !s.isBlank()).toList());
        for (Iterator<String> it = hashes.iterator(); it.hasNext(); ) {
            if (passwordEncoder.matches(candidate, it.next())) {
                it.remove(); // single use
                u.setRecoveryCodes(String.join("\n", hashes) + (hashes.isEmpty() ? "" : "\n"));
                userRepository.save(u);
                auditService.log(u.getId(), "2FA_RECOVERY_CODE_USED", "USER", u.getId(),
                        "Signed in with a recovery code (" + hashes.size() + " left)");
                return true;
            }
        }
        return false;
    }

    private static String newRecoveryCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I confusion
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String normalizeRecovery(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase();
    }
}
