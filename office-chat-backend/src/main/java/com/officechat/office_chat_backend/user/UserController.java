package com.officechat.office_chat_backend.user;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // Requires a valid JWT (enforced globally in SecurityConfig) — returns the office directory
    @GetMapping
    public List<UserResponse> getAllUsers() {
        return userService.getAllActiveUsers();
    }
}