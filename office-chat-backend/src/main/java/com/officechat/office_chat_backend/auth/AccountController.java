package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The signed-in user's own security settings: two-step verification and active sessions. */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final TwoFactorService twoFactorService;
    private final SessionService sessionService;

    private static String currentSid(HttpServletRequest request) {
        Object sid = request.getAttribute(JwtService.SESSION_CLAIM);
        return sid != null ? sid.toString() : null;
    }

    @GetMapping("/2fa")
    public Map<String, Object> status(@AuthenticationPrincipal User currentUser) {
        return twoFactorService.status(currentUser.getId());
    }

    @PostMapping("/2fa/setup")
    public Map<String, String> setup(@AuthenticationPrincipal User currentUser) {
        return twoFactorService.beginSetup(currentUser.getId());
    }

    /** Body: { code } → { recoveryCodes: [...] } */
    @PostMapping("/2fa/enable")
    public Map<String, List<String>> enable(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, String> body) {
        return Map.of("recoveryCodes", twoFactorService.confirmSetup(currentUser.getId(), body.get("code")));
    }

    /** Body: { password, code } */
    @PostMapping("/2fa/disable")
    public void disable(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, String> body) {
        twoFactorService.disable(currentUser.getId(), body.get("password"), body.get("code"));
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@AuthenticationPrincipal User currentUser, HttpServletRequest request) {
        return sessionService.list(currentUser.getId(), currentSid(request));
    }

    @DeleteMapping("/sessions/{id}")
    public void revoke(@AuthenticationPrincipal User currentUser, @PathVariable String id) {
        sessionService.revoke(currentUser.getId(), id);
    }

    /** Signs out every device except this one. */
    @PostMapping("/sessions/revoke-others")
    public Map<String, Integer> revokeOthers(@AuthenticationPrincipal User currentUser, HttpServletRequest request) {
        return Map.of("signedOut", sessionService.revokeAllExcept(currentUser.getId(), currentSid(request)));
    }

    /** Signs out this device on the server too, so the token stops working immediately. */
    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal User currentUser, HttpServletRequest request) {
        String sid = currentSid(request);
        if (sid != null) sessionService.revoke(currentUser.getId(), sid);
    }
}
