package com.officechat.office_chat_backend.template;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.user.Role;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Quick replies ("/" shortcuts). Personal ones for everyone; shared ones are managed by admins. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/templates")
public class MessageTemplateController {

    private static final int MAX_PER_USER = 100;

    private final MessageTemplateRepository repository;

    public record TemplateView(Long id, String shortcut, String title, String content, boolean shared, boolean editable) {}

    /** Body for create/update: { shortcut, title, content, shared? } */
    public record TemplateRequest(String shortcut, String title, String content, Boolean shared) {}

    @GetMapping
    public List<TemplateView> list(@AuthenticationPrincipal User user) {
        return repository.findVisibleTo(user.getId()).stream().map(t -> view(t, user)).toList();
    }

    @PostMapping
    public TemplateView create(@AuthenticationPrincipal User user, @RequestBody TemplateRequest body) {
        boolean shared = Boolean.TRUE.equals(body.shared());
        if (shared) requireAdmin(user);
        else if (repository.countByOwnerId(user.getId()) >= MAX_PER_USER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can keep up to " + MAX_PER_USER + " quick replies");
        }
        MessageTemplate t = MessageTemplate.builder().ownerId(shared ? null : user.getId()).build();
        return view(save(t, body, user), user);
    }

    @PutMapping("/{id}")
    public TemplateView update(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody TemplateRequest body) {
        return view(save(editable(id, user), body, user), user);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        repository.delete(editable(id, user));
        return Map.of("deleted", true);
    }

    private MessageTemplate save(MessageTemplate t, TemplateRequest body, User user) {
        String shortcut = body.shortcut() == null ? "" : body.shortcut().trim().toLowerCase().replaceFirst("^/", "");
        if (!shortcut.matches("[a-z0-9][a-z0-9_-]{0,29}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Shortcut: letters, numbers, - or _ (up to 30)");
        }
        String content = body.content() == null ? "" : body.content().trim();
        if (content.isEmpty() || content.length() > 5000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Reply text is required (up to 5000 characters)");
        }
        boolean clash = repository.findVisibleTo(user.getId()).stream()
                .anyMatch(o -> o.getShortcut().equals(shortcut) && !o.getId().equals(t.getId())
                        && java.util.Objects.equals(o.getOwnerId(), t.getOwnerId()));
        if (clash) throw new ApiException(HttpStatus.CONFLICT, "/" + shortcut + " is already used");
        String title = body.title() == null || body.title().isBlank() ? shortcut : body.title().trim();
        t.setShortcut(shortcut);
        t.setTitle(title.length() > 80 ? title.substring(0, 80) : title);
        t.setContent(content);
        t.setUpdatedAt(Instant.now());
        return repository.save(t);
    }

    private MessageTemplate editable(Long id, User user) {
        MessageTemplate t = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quick reply not found"));
        if (t.getOwnerId() == null) requireAdmin(user);
        else if (!t.getOwnerId().equals(user.getId())) throw new ApiException(HttpStatus.NOT_FOUND, "Quick reply not found");
        return t;
    }

    private static void requireAdmin(User user) {
        if (user.getRole() != Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Only admins manage shared quick replies");
    }

    private static TemplateView view(MessageTemplate t, User user) {
        boolean shared = t.getOwnerId() == null;
        return new TemplateView(t.getId(), t.getShortcut(), t.getTitle(), t.getContent(), shared,
                !shared || user.getRole() == Role.ADMIN);
    }
}
