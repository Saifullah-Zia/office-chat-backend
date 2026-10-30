package com.officechat.office_chat_backend.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String department;

    @Column(name = "profile_pic_url")
    private String profilePicUrl;

    @Column(name = "work_status")
    private String workStatus;

    @Builder.Default
    private boolean active = true;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    // ── Two-factor authentication (authenticator app / TOTP) ─────────────
    @JsonIgnore
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    /** Secret shown during setup, promoted to totpSecret once the user proves they scanned it. */
    @JsonIgnore
    @Column(name = "totp_pending_secret", length = 64)
    private String totpPendingSecret;

    @Column(name = "totp_enabled")
    private Boolean totpEnabled;

    /** Last accepted TOTP time-step, so a code can't be replayed. */
    @JsonIgnore
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    /** BCrypt hashes of single-use recovery codes, newline-separated. */
    @JsonIgnore
    @Column(name = "recovery_codes", columnDefinition = "TEXT")
    private String recoveryCodes;

    public boolean isTwoFactorEnabled() {
        return Boolean.TRUE.equals(totpEnabled) && totpSecret != null;
    }

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // ---- UserDetails contract ----

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email; // we log in with email, not a separate username field
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return active; }
}