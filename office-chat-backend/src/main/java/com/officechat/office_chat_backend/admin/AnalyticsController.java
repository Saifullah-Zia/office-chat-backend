package com.officechat.office_chat_backend.admin;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final ExportService exportService;

    @GetMapping("/api/admin/analytics")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.overview(days);
    }

    /** Conversations to pick from for a compliance export (search by group or person). */
    @GetMapping("/api/admin/export/conversations")
    @PreAuthorize("hasRole('ADMIN')")
    public java.util.List<Map<String, Object>> exportableConversations(@RequestParam(required = false) String q) {
        return exportService.listConversations(q);
    }

    /** Compliance export of one conversation as CSV. Logged in the audit trail. */
    @GetMapping("/api/admin/export/conversations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportConversation(@AuthenticationPrincipal User admin, @PathVariable Long id) {
        ExportService.CsvExport export = exportService.exportConversationCsv(id, admin);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }

    /** "Download my data" for any signed-in user. */
    @GetMapping("/api/account/export")
    public ResponseEntity<Map<String, Object>> exportMyData(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("my-office-chat-data.json").build().toString())
                .body(exportService.exportMyData(user));
    }
}
