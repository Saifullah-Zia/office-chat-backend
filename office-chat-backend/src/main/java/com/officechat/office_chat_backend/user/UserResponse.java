package com.officechat.office_chat_backend.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private String department;
    private Role role;
    private String profilePicUrl;
    private String workStatus;
    private boolean active;
    private Instant lastSeenAt;
    private String jobTitle;
    private Long managerId;
    /** True when the email was withheld from this viewer (providers' emails are admin-only). */
    private boolean emailHidden;

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getDepartment(),
                user.getRole(),
                user.getProfilePicUrl(),
                user.getWorkStatus(),
                user.isActive(),
                user.getLastSeenAt(),
                user.getJobTitle(),
                user.getManagerId(),
                false
        );
    }

    /** Providers' emails are visible only to admins and to the provider themself. */
    public UserResponse visibleTo(boolean viewerIsAdmin, Long viewerId) {
        if (role != Role.PROVIDER || viewerIsAdmin || id.equals(viewerId)) return this;
        return new UserResponse(id, name, "", department, role, profilePicUrl, workStatus, active, lastSeenAt,
                jobTitle, managerId, true);
    }
}