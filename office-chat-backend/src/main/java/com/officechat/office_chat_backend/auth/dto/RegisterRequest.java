package com.officechat.office_chat_backend.auth.dto;


import com.officechat.office_chat_backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String name,
        @Email @NotBlank String email,
        @Size(min = 8, message = "Password must be at least 8 characters") String password,
        Role role,
        String department
) {}