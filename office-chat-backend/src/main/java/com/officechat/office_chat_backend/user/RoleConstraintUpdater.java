package com.officechat.office_chat_backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Hibernate creates a CHECK constraint listing the Role values when the table is first made, and
 * ddl-auto=update never widens it. Rebuild it from the enum at startup so new roles can be saved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleConstraintUpdater {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void syncRoleConstraint() {
        String allowed = Arrays.stream(Role.values()).map(r -> "'" + r.name() + "'").collect(Collectors.joining(", "));
        try {
            jdbc.execute("alter table users drop constraint if exists users_role_check");
            jdbc.execute("alter table users add constraint users_role_check check (role in (" + allowed + "))");
        } catch (Exception e) {
            log.warn("Could not update users_role_check: {}", e.getMessage());
        }
    }
}
