package com.officechat.office_chat_backend.qr;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** QR codes for clients: made by admins, scanned by anyone. */
@RestController
@RequiredArgsConstructor
public class QrCodeController {

    /** Says nothing about why: visitors are the client's customers. */
    private static final String UNAVAILABLE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex"><title>Link unavailable</title></head>
            <body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif;background:#f8fafc;color:#0f172a">
            <div style="text-align:center;padding:24px"><h1 style="font-size:1.3rem">This link is currently unavailable</h1>
            <p style="color:#64748b">Please contact the business directly.</p></div></body></html>
            """;

    private final QrCodeService service;

    public record SaveRequest(String clientName, String url, Boolean managed) {}

    public record BlockRequest(boolean blocked) {}

    @GetMapping("/api/admin/qr-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<QrCodeService.View> list() {
        return service.list();
    }

    @PostMapping("/api/admin/qr-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public QrCodeService.View create(@AuthenticationPrincipal User admin, @RequestBody SaveRequest r) {
        return service.create(admin.getId(), r.clientName(), r.url(), Boolean.TRUE.equals(r.managed()));
    }

    @PutMapping("/api/admin/qr-codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public QrCodeService.View update(@AuthenticationPrincipal User admin, @PathVariable Long id, @RequestBody SaveRequest r) {
        return service.update(admin.getId(), id, r.clientName(), r.url());
    }

    @PutMapping("/api/admin/qr-codes/{id}/blocked")
    @PreAuthorize("hasRole('ADMIN')")
    public QrCodeService.View setBlocked(@AuthenticationPrincipal User admin, @PathVariable Long id, @RequestBody BlockRequest r) {
        return service.setBlocked(admin.getId(), id, r.blocked());
    }

    @DeleteMapping("/api/admin/qr-codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@AuthenticationPrincipal User admin, @PathVariable Long id) {
        service.delete(admin.getId(), id);
    }

    /** A scan (the website's /q/{code} leads here). Never cached, so blocking takes effect at once. */
    @GetMapping("/api/qr/{code}")
    public ResponseEntity<String> scan(@PathVariable String code) {
        String target = service.resolve(code);
        if (target != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target))
                    .cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(UNAVAILABLE);
    }
}
