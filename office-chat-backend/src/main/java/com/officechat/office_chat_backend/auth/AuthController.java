package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.auth.dto.AuthResponse;
import com.officechat.office_chat_backend.auth.dto.LoginRequest;
import com.officechat.office_chat_backend.auth.dto.RegisterRequest;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RateLimiter rateLimiter;
    private final SessionService sessionService;
    private final TwoFactorService twoFactorService;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        User user = userService.register(request);
        String token = jwtService.generateToken(user, sessionService.create(user, http).getId());
        return AuthResponse.ok(token, user);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String cleanEmail = request.email() != null ? request.email().trim().toLowerCase() : "";

        // Brute-force protection: per account and per client address.
        String accountKey = "login:" + cleanEmail;
        String ipKey = "login-ip:" + http.getRemoteAddr();
        if (!rateLimiter.tryAcquire(accountKey, 10, Duration.ofMinutes(15))
                || !rateLimiter.tryAcquire(ipKey, 50, Duration.ofMinutes(15))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Please wait 15 minutes and try again.");
        }

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(cleanEmail, request.password())
        );
        rateLimiter.reset(accountKey); // successful login clears the account counter

        User user = userService.loadUserByUsername(cleanEmail);
        if (user.isTwoFactorEnabled()) {
            // Password was right; now prove possession of the authenticator.
            return AuthResponse.mfa(jwtService.generateMfaToken(user));
        }
        String token = jwtService.generateToken(user, sessionService.create(user, http).getId());
        return AuthResponse.ok(token, user);
    }

    /** Body: { mfaToken, code } — code is a 6-digit authenticator code or a recovery code. */
    @PostMapping("/login/verify")
    public AuthResponse verify(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String mfaToken = body.get("mfaToken");
        String code = body.get("code");
        String email;
        try {
            if (mfaToken == null || !jwtService.isMfaToken(mfaToken)) throw new IllegalArgumentException();
            email = jwtService.extractEmail(mfaToken);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Your sign-in expired. Please enter your password again.");
        }

        rateLimiter.check("mfa:" + email, 6, Duration.ofMinutes(10),
                "Too many incorrect codes. Please wait 10 minutes and sign in again.");

        User user = userService.loadUserByUsername(email);
        if (!user.isActive() || !twoFactorService.verifyLoginCode(user, code)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That code isn't right. Check your authenticator app and try again.");
        }
        rateLimiter.reset("mfa:" + email);
        String token = jwtService.generateToken(user, sessionService.create(user, http).getId());
        return AuthResponse.ok(token, user);
    }
}
