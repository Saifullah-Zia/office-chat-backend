package com.officechat.office_chat_backend.ai;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    /** Lets the app hide the AI buttons when no API key is configured. */
    @GetMapping("/api/ai/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", aiService.isEnabled());
    }

    /** Body: { targetLanguage } */
    @PostMapping("/api/messages/{id}/translate")
    public Map<String, String> translate(@AuthenticationPrincipal User user, @PathVariable Long id,
                                         @RequestBody Map<String, String> body) {
        return Map.of("translation", aiService.translate(user, id, body.get("targetLanguage")));
    }

    @PostMapping("/api/conversations/{id}/summary")
    public Map<String, String> summarize(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return Map.of("summary", aiService.summarize(user, id));
    }
}
