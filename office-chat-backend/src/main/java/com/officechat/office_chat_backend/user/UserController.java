package com.officechat.office_chat_backend.user;

import com.officechat.office_chat_backend.conversation.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.officechat.office_chat_backend.audit.AuditService auditService;

    @GetMapping
    public List<UserResponse> getAllUsers() {
        return userService.getAllActiveUsers();
    }

    // ── Blocking ─────────────────────────────────────────────────────────

    @GetMapping("/blocked")
    public List<Long> getBlockedUsers(@AuthenticationPrincipal User currentUser) {
        return conversationService.getBlockedUserIds(currentUser.getId());
    }

    @PostMapping("/{userId}/block")
    public void blockUser(@AuthenticationPrincipal User currentUser, @PathVariable Long userId) {
        conversationService.blockUser(currentUser.getId(), userId);
        notifyBlockChange(currentUser.getId(), userId, true);
    }

    @DeleteMapping("/{userId}/block")
    public void unblockUser(@AuthenticationPrincipal User currentUser, @PathVariable Long userId) {
        conversationService.unblockUser(currentUser.getId(), userId);
        notifyBlockChange(currentUser.getId(), userId, false);
    }

    private void notifyBlockChange(Long blockerId, Long blockedId, boolean blocked) {
        messagingTemplate.convertAndSend(
                "/topic/user.block",
                (Object) Map.of("blockerId", blockerId, "blockedId", blockedId, "blocked", blocked)
        );
    }

    @PutMapping("/status")
    public UserResponse updateStatus(
            @AuthenticationPrincipal User currentUser,
            @RequestBody Map<String, String> body
    ) {
        String status = body != null ? body.get("status") : null;
        User updated = userService.updateWorkStatus(currentUser.getId(), status);
        messagingTemplate.convertAndSend(
                "/topic/user.status",
                (Object) Map.of("userId", currentUser.getId(), "status", status != null ? status : "")
        );
        return UserResponse.from(updated);
    }

    // ── Admin Endpoints ──────────────────────────────────────────────────

    /** List ALL users including deactivated ones (admin only) */
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserResponse> getAllUsersAdmin() {
        return userService.getAllUsersIncludingInactive();
    }

    /** Deactivate (soft-delete) a user account (admin only) */
    @DeleteMapping("/admin/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deactivateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal User currentAdmin
    ) {
        if (currentAdmin.getId().equals(userId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "You cannot deactivate your own account"));
        }
        User user = userService.setActive(userId, false);
        auditService.log(currentAdmin.getId(), "USER_DEACTIVATED", "USER", userId, "Deactivated " + user.getName());
        // Broadcast user removal event so other clients update their UI
        messagingTemplate.convertAndSend("/topic/user.removed", (Object) Map.of("userId", userId));
        return ResponseEntity.ok(Map.of(
                "message", "User " + user.getName() + " has been deactivated",
                "userId", userId
        ));
    }

    /** Reactivate a previously deactivated user (admin only) */
    @PutMapping("/admin/{userId}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reactivateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal User currentAdmin
    ) {
        User user = userService.setActive(userId, true);
        auditService.log(currentAdmin.getId(), "USER_REACTIVATED", "USER", userId, "Reactivated " + user.getName());
        return ResponseEntity.ok(Map.of(
                "message", "User " + user.getName() + " has been reactivated",
                "userId", userId
        ));
    }

    /** Change a user's role (admin only) */
    @PutMapping("/admin/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> changeUserRole(
            @PathVariable Long userId,
            @AuthenticationPrincipal User currentAdmin,
            @RequestBody Map<String, String> body
    ) {
        if (currentAdmin.getId().equals(userId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "You cannot change your own role"));
        }
        String roleName = body.get("role");
        Role role = Role.valueOf(roleName);
        User user = userService.updateRole(userId, role);
        auditService.log(currentAdmin.getId(), "USER_ROLE_CHANGED", "USER", userId,
                "Changed " + user.getName() + "'s role to " + role);
        return ResponseEntity.ok(Map.of(
                "message", "User " + user.getName() + " role changed to " + role,
                "userId", userId,
                "role", role.name()
        ));
    }
}