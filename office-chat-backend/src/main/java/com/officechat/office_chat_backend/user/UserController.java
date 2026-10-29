package com.officechat.office_chat_backend.user;

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
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping
    public List<UserResponse> getAllUsers() {
        return userService.getAllActiveUsers();
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
            @PathVariable Long userId
    ) {
        User user = userService.setActive(userId, true);
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
        return ResponseEntity.ok(Map.of(
                "message", "User " + user.getName() + " role changed to " + role,
                "userId", userId,
                "role", role.name()
        ));
    }
}