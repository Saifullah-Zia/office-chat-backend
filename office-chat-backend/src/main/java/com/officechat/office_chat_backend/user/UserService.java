package com.officechat.office_chat_backend.user;

import com.officechat.office_chat_backend.auth.dto.RegisterRequest;
import com.officechat.office_chat_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User loadUserByUsername(String email) throws UsernameNotFoundException {
        if (email == null) throw new UsernameNotFoundException("Email cannot be null");
        String cleanEmail = email.trim().toLowerCase();
        return userRepository.findByEmailIgnoreCase(cleanEmail)
                .orElseGet(() -> userRepository.findByEmail(email)
                        .orElseThrow(() -> new UsernameNotFoundException("No user found with email " + email)));
    }

    // Used by @PreAuthorize on the register endpoint — true only before any account exists,
    // so the very first admin can be created without needing a token to already have one.
    public boolean isFirstUser() {
        return userRepository.count() == 0;
    }

    public User register(RegisterRequest request) {
        String cleanEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        if (userRepository.existsByEmailIgnoreCase(cleanEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        boolean firstUser = isFirstUser();

        if (!firstUser) {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
                    || !hasAdminRole(auth)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Registration is restricted to Admin users");
            }
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                // the first-ever account is always forced to ADMIN, regardless of what the request says
                .role(firstUser ? Role.ADMIN : (request.role() != null ? request.role() : Role.EMPLOYEE))
                .department(request.department())
                .active(true)
                .build();

        return userRepository.save(user);
    }

    private boolean hasAdminRole(org.springframework.security.core.Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
    }

    public List<UserResponse> getAllActiveUsers() {
        return userRepository.findAll().stream()
                .filter(User::isActive)
                .map(UserResponse::from)
                .toList();
    }

    public List<UserResponse> getAllUsersIncludingInactive() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    public User setActive(Long userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(active);
        return userRepository.save(user);
    }
}