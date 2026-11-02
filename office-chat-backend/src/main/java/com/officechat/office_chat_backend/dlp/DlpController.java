package com.officechat.office_chat_backend.dlp;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/dlp")
@PreAuthorize("hasRole('ADMIN')")
public class DlpController {

    private final DlpService dlpService;

    @GetMapping
    public Map<String, String> get() {
        return Map.of("mode", dlpService.mode());
    }

    /** Body: { mode: "off" | "warn" | "block" } */
    @PutMapping
    public Map<String, String> set(@AuthenticationPrincipal User admin, @RequestBody Map<String, String> body) {
        dlpService.setMode(body.get("mode"), admin.getId());
        return Map.of("mode", dlpService.mode());
    }
}
