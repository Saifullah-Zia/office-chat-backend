package com.officechat.office_chat_backend.user;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.auth.dto.RegisterRequest;
import com.officechat.office_chat_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Bulk onboarding: the admin uploads a CSV (parsed in the browser) and each row goes through the
 * normal registration rules. Every new account gets a random temporary password, returned once.
 */
@RestController
@RequiredArgsConstructor
public class UserImportController {

    private static final int MAX_ROWS = 500;

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public record ImportRow(String name, String email, String department, String jobTitle, String role) {}
    public record ImportResult(int row, String email, String status, String message, String tempPassword) {}

    /** Body: { rows: [{ name, email, department?, jobTitle?, role? }], dryRun? } */
    @PostMapping("/api/users/admin/import")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ImportResult> importUsers(@AuthenticationPrincipal User admin, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = body.get("rows") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        if (raw.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "The file has no rows");
        if (raw.size() > MAX_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST, "Import up to " + MAX_ROWS + " people at a time");

        List<ImportResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> r = raw.get(i);
            ImportRow row = new ImportRow(str(r.get("name")), str(r.get("email")).toLowerCase(),
                    str(r.get("department")), str(r.get("jobTitle")), str(r.get("role")).toUpperCase());
            int line = i + 2; // +1 for 1-based, +1 for the header row
            String problem = validate(row, seen);
            if (problem != null) {
                results.add(new ImportResult(line, row.email(), "error", problem, null));
                continue;
            }
            if (userRepository.existsByEmailIgnoreCase(row.email())) {
                results.add(new ImportResult(line, row.email(), "skipped", "Already has an account", null));
                continue;
            }
            if (dryRun) {
                results.add(new ImportResult(line, row.email(), "ready", null, null));
                continue;
            }
            try {
                String password = com.officechat.office_chat_backend.auth.PasswordService.generateTemporary();
                Role role = row.role().isEmpty() ? Role.EMPLOYEE : Role.valueOf(row.role());
                User user = userService.register(new RegisterRequest(row.name(), row.email(), password, role,
                        row.department().isEmpty() ? null : row.department()));
                if (!row.jobTitle().isEmpty()) {
                    user.setJobTitle(row.jobTitle().length() > 100 ? row.jobTitle().substring(0, 100) : row.jobTitle());
                }
                user.setMustChangePassword(true); // they pick their own at first login
                userRepository.save(user);
                results.add(new ImportResult(line, row.email(), "created", null, password));
                created++;
            } catch (ApiException e) {
                results.add(new ImportResult(line, row.email(), "error", e.getMessage(), null));
            }
        }
        if (created > 0) auditService.log(admin.getId(), "USERS_IMPORTED", "USER", null, created + " account(s) created from CSV");
        return results;
    }

    private static String validate(ImportRow row, Set<String> seen) {
        if (row.name().isEmpty()) return "Name is missing";
        if (row.name().length() > 100) return "Name is too long";
        if (!row.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return "Email isn't valid";
        if (!seen.add(row.email())) return "Same email appears twice in the file";
        if (!row.role().isEmpty() && java.util.Arrays.stream(Role.values()).noneMatch(r -> r.name().equals(row.role()))) {
            return "Role must be EMPLOYEE, PROVIDER or ADMIN";
        }
        return null;
    }


    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
