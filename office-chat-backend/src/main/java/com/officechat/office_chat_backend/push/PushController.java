package com.officechat.office_chat_backend.push;

import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/push")
public class PushController {

    private final PushService pushService;
    private final VapidKeys vapidKeys;

    /** The browser needs this as applicationServerKey when subscribing. */
    @GetMapping("/vapid-public-key")
    public Map<String, String> publicKey() {
        return Map.of("key", vapidKeys.getPublicKeyBase64());
    }

    /** Body: the browser's PushSubscription JSON: { endpoint, keys: { p256dh, auth } } */
    @PostMapping("/subscribe")
    @SuppressWarnings("unchecked")
    public void subscribe(@AuthenticationPrincipal User user, @RequestBody Map<String, Object> body) {
        Map<String, Object> keys = body.get("keys") instanceof Map<?, ?> k ? (Map<String, Object>) k : Map.of();
        pushService.subscribe(user.getId(), (String) body.get("endpoint"), (String) keys.get("p256dh"), (String) keys.get("auth"));
    }

    @PostMapping("/unsubscribe")
    public void unsubscribe(@AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        pushService.unsubscribe(user.getId(), body.get("endpoint"));
    }

    /** Android app: Body { token, platform: "android" } (its Firebase token). */
    @PostMapping("/native/register")
    public void registerNative(@AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        pushService.registerNative(user.getId(), body.get("token"), body.get("platform"));
    }

    /** Android app: Body { delaySeconds } — a test ring to my phone(s) after the delay (close the app meanwhile). */
    @PostMapping("/native/test")
    public Map<String, Integer> testNative(@AuthenticationPrincipal User user, @RequestBody(required = false) Map<String, Integer> body) {
        int delay = body != null && body.get("delaySeconds") != null ? body.get("delaySeconds") : 30;
        return Map.of("phones", pushService.sendNativeTest(user.getId(), delay));
    }

    /** Android app, on sign-out: Body { token } */
    @PostMapping("/native/unregister")
    public void unregisterNative(@AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        pushService.unregisterNative(user.getId(), body.get("token"));
    }
}
