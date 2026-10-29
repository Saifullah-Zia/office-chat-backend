package com.officechat.office_chat_backend.link;

import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LinkPreviewController {

    private final LinkPreviewService linkPreviewService;
    private final RateLimiter rateLimiter;

    @GetMapping("/api/link-preview")
    public Map<String, String> preview(@AuthenticationPrincipal User currentUser, @RequestParam String url) {
        rateLimiter.check("preview:" + currentUser.getId(), 60, Duration.ofMinutes(1),
                "Too many link previews requested. Please wait a moment.");
        return linkPreviewService.preview(url);
    }
}
