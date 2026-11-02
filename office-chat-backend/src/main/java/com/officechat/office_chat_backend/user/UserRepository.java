package com.officechat.office_chat_backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByProfilePicUrl(String profilePicUrl);
    java.util.List<User> findByRoleAndActiveTrue(Role role);
    long countByActive(boolean active);
}