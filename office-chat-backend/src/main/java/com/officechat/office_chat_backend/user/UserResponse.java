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
                user.getLastSeenAt()
        );
    }
}