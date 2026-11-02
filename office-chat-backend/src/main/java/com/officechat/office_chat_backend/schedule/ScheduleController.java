package com.officechat.office_chat_backend.schedule;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    private static Instant parseInstant(Object v) {
        if (v == null) return null;
        try {
            return Instant.parse(v.toString());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid date/time");
        }
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

    /** Body: { conversationId, content, sendAt (ISO instant), attachmentId? } */
    @PostMapping("/api/scheduled")
    public Map<String, Object> schedule(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, Object> body) {
        return scheduleService.schedule(currentUser, asLong(body.get("conversationId")),
                (String) body.get("content"), parseInstant(body.get("sendAt")),
                body.get("attachmentId") == null ? null : asLong(body.get("attachmentId")));
    }

    @GetMapping("/api/scheduled")
    public List<Map<String, Object>> listScheduled(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) Long conversationId
    ) {
        return scheduleService.listScheduled(currentUser.getId(), conversationId);
    }

    @DeleteMapping("/api/scheduled/{id}")
    public void cancelScheduled(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        scheduleService.cancelScheduled(currentUser.getId(), id);
    }

    /** Body: { messageId?, note?, remindAt (ISO instant) } */
    @PostMapping("/api/reminders")
    public Map<String, Object> remind(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, Object> body) {
        return scheduleService.remind(currentUser, asLong(body.get("messageId")),
                (String) body.get("note"), parseInstant(body.get("remindAt")));
    }

    @GetMapping("/api/reminders")
    public List<Map<String, Object>> listReminders(@AuthenticationPrincipal User currentUser) {
        return scheduleService.listReminders(currentUser.getId());
    }

    /** Body: { status: DONE | CANCELLED } */
    @PutMapping("/api/reminders/{id}")
    public void updateReminder(@AuthenticationPrincipal User currentUser, @PathVariable Long id,
                               @RequestBody Map<String, String> body) {
        scheduleService.updateReminder(currentUser.getId(), id, body.get("status"));
    }

    /** Body: { until (ISO instant) } */
    @PostMapping("/api/reminders/{id}/snooze")
    public Map<String, Object> snooze(@AuthenticationPrincipal User currentUser, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return scheduleService.snoozeReminder(currentUser.getId(), id, parseInstant(body.get("until")));
    }
}
