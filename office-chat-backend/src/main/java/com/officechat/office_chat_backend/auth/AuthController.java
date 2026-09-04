package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.auth.dto.AuthResponse;
import com.officechat.office_chat_backend.auth.dto.LoginRequest;
import com.officechat.office_chat_backend.auth.dto.RegisterRequest;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        String cleanEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(cleanEmail, request.password())
        );

        User user = userService.loadUserByUsername(cleanEmail);
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}