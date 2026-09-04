package com.officechat.office_chat_backend.auth.dto;

import com.officechat.office_chat_backend.user.Role;

public record AuthResponse(
        String token,
        Long userId,
        String name,
        String email,
        Role role
) {}