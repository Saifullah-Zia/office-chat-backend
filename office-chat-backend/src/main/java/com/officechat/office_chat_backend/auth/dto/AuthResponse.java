package com.officechat.office_chat_backend.auth.dto;

import com.officechat.office_chat_backend.user.Role;

/**
 * Login result. When two-factor auth is on, the password step returns
 * {@code mfaRequired = true} plus a short-lived {@code mfaToken} and no {@code token};
 * the client then calls /api/auth/login/verify with the 6-digit code.
 */
public record AuthResponse(
        String token,
        Long userId,
        String name,
        String email,
        Role role,
        boolean mfaRequired,
        String mfaToken
) {
    public static AuthResponse ok(String token, com.officechat.office_chat_backend.user.User user) {
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole(), false, null);
    }

    public static AuthResponse mfa(String mfaToken) {
        return new AuthResponse(null, null, null, null, null, true, mfaToken);
    }
}
