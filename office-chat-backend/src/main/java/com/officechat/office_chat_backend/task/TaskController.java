package com.officechat.office_chat_backend.task;

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
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Body: { title, notes?, conversationId?, messageId?, assigneeId?, dueAt? (ISO instant) } */
    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal User currentUser, @RequestBody Map<String, Object> body) {
        Instant dueAt = null;
        Object d = body.get("dueAt");
        if (d != null && !d.toString().isBlank()) {
            try {
                dueAt = Instant.parse(d.toString());
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid due date");
            }
        }
        return taskService.create(currentUser, (String) body.get("title"), (String) body.get("notes"),
                asLong(body.get("conversationId")), asLong(body.get("messageId")),
                asLong(body.get("assigneeId")), dueAt);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "assigned") String scope,
            @RequestParam(required = false) Long conversationId
    ) {
        return taskService.list(currentUser.getId(), scope, conversationId);
    }

    @GetMapping("/count")
    public Map<String, Long> count(@AuthenticationPrincipal User currentUser) {
        return Map.of("open", taskService.countOpenAssigned(currentUser.getId()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@AuthenticationPrincipal User currentUser, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return taskService.update(currentUser, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        taskService.delete(currentUser, id);
    }
}
