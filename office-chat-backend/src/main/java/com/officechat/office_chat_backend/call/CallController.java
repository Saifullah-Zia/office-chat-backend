package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.call.dto.CallResponse;
import com.officechat.office_chat_backend.call.dto.InitiateCallRequest;
import com.officechat.office_chat_backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallController {

    private final CallService callService;

    private final GroupCallPresenceService presenceService;
    private final CallRinger callRinger;
    private final com.officechat.office_chat_backend.user.UserRepository userRepository;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /** Group calls: 'joined' and, every minute after, 'still here'. */
    @PostMapping("/{id}/presence")
    public java.util.Map<String, Object> checkIn(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        return presenceService.checkIn(id, currentUser);
    }

    @DeleteMapping("/{id}/presence")
    public void leave(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        presenceService.leave(id, currentUser);
    }

    /** The group call running in a chat right now (for the Join call banner). */
    @GetMapping("/active")
    public java.util.Map<String, Object> active(@AuthenticationPrincipal User currentUser, @RequestParam Long conversationId) {
        return presenceService.active(conversationId, currentUser.getId());
    }

    @PostMapping
    public CallResponse initiate(@AuthenticationPrincipal User currentUser, @Valid @RequestBody InitiateCallRequest request) {
        return CallResponse.from(callService.initiate(request.conversationId(), currentUser, request.type()));
    }

    /** The call ringing for me right now, if any: an app opened from the call notification shows it. */
    @GetMapping("/incoming")
    public java.util.Map<String, Object> incoming(@AuthenticationPrincipal User currentUser) {
        java.util.Map<String, Object> ringing = callRinger.ringingFor(currentUser.getId());
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("call", ringing);
        return out;
    }

    /** Body: { userId, token }. "Decline" on the notification; the service worker has no login, so the push carries a token for this call. */
    @PostMapping("/{id}/push-decline")
    public void declineFromNotification(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        Object uid = body.get("userId");
        Long userId = uid instanceof Number n ? n.longValue() : null;
        Call call = callService.declineFromNotification(id, userId, (String) body.get("token"), userRepository);
        if (call == null) return;
        // Stop the caller's ringing, as the app's own Decline does.
        java.util.Map<String, Object> hangup = new java.util.LinkedHashMap<>();
        hangup.put("type", "HANGUP");
        hangup.put("conversationId", call.getConversation().getId());
        hangup.put("callId", call.getId());
        hangup.put("fromUserId", userId);
        hangup.put("fromUserName", userRepository.findById(userId).map(User::getName).orElse(""));
        messagingTemplate.convertAndSendToUser(call.getInitiator().getEmail(), "/queue/call", hangup);
    }

    @PostMapping("/{id}/accept")
    public CallResponse accept(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        return CallResponse.from(callService.accept(id, currentUser));
    }

    @PostMapping("/{id}/reject")
    public CallResponse reject(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        return CallResponse.from(callService.reject(id, currentUser));
    }

    @PostMapping("/{id}/end")
    public CallResponse end(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        return CallResponse.from(callService.end(id, currentUser));
    }

    @GetMapping("/history")
    public List<CallResponse> myHistory(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return callService.getMyHistory(currentUser.getId(), Math.min(limit, 200))
                .stream().map(CallResponse::from).toList();
    }

    @GetMapping("/conversation/{conversationId}")
    public List<CallResponse> history(@AuthenticationPrincipal User currentUser, @PathVariable Long conversationId) {
        return callService.getHistory(conversationId, currentUser.getId())
                .stream().map(CallResponse::from).toList();
    }
}