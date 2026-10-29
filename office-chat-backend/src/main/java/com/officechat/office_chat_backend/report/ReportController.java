package com.officechat.office_chat_backend.report;

import com.officechat.office_chat_backend.audit.AuditLog;
import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final AuditService auditService;

    /** Body: { targetUserId?, messageId?, reason, details? } — one of targetUserId / messageId is required. */
    @PostMapping("/api/reports")
    public Map<String, Object> create(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, Object> body) {
        return reportService.create(
                currentUser,
                asLong(body.get("targetUserId")),
                asLong(body.get("messageId")),
                (String) body.get("reason"),
                (String) body.get("details")
        );
    }

    // ── Admin ────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/reports")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "OPEN") String status) {
        return reportService.list(status);
    }

    @GetMapping("/api/admin/reports/count")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Long> countOpen() {
        return Map.of("open", reportService.countOpen());
    }

    /** Body: { status: OPEN|RESOLVED|DISMISSED, note? } */
    @PutMapping("/api/admin/reports/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> resolve(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        return reportService.resolve(id, currentUser, body.get("status"), body.get("note"));
    }

    @GetMapping("/api/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action
    ) {
        Page<AuditLog> result = auditService.list(action, page, size);
        return Map.of(
                "items", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        );
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
