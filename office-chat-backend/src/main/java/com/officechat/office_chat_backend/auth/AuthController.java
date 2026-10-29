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
    private final com.officechat.office_chat_backend.common.RateLimiter rateLimiter;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String cleanEmail = request.email() != null ? request.email().trim().toLowerCase() : "";

        // Brute-force protection: per account and per client address.
        String accountKey = "login:" + cleanEmail;
        String ipKey = "login-ip:" + http.getRemoteAddr();
        if (!rateLimiter.tryAcquire(accountKey, 10, java.time.Duration.ofMinutes(15))
                || !rateLimiter.tryAcquire(ipKey, 50, java.time.Duration.ofMinutes(15))) {
            throw new com.officechat.office_chat_backend.common.ApiException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Please wait 15 minutes and try again.");
        }

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(cleanEmail, request.password())
        );
        rateLimiter.reset(accountKey); // successful login clears the account counter

        User user = userService.loadUserByUsername(cleanEmail);
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}