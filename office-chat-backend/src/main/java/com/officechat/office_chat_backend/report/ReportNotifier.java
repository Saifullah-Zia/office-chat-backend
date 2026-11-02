package com.officechat.office_chat_backend.report;

import com.officechat.office_chat_backend.mail.EmailService;
import com.officechat.office_chat_backend.user.Role;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tells admins about new reports, and the reporter when an admin has dealt with theirs
 * (live in the app via /user/queue/reports, plus email when Resend is set up).
 * The reported person is never told who reported them.
 */
@Component
@RequiredArgsConstructor
public class ReportNotifier {

    private static final Map<String, String> REASON_LABELS = Map.of(
            "SPAM", "Spam", "HARASSMENT", "Harassment", "INAPPROPRIATE", "Inappropriate content",
            "IMPERSONATION", "Impersonation", "SECURITY", "Security concern", "OTHER", "Something else");

    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final EmailService emailService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    public void newReport(Report r, User reporter, String targetName, long openCount) {
        String reason = REASON_LABELS.getOrDefault(r.getReason(), r.getReason());
        String link = base() + "/?open=reports";
        for (User admin : admins()) {
            Map<String, Object> ev = event("NEW", r);
            ev.put("reporterName", reporter.getName());
            ev.put("targetName", targetName);
            ev.put("reason", reason);
            ev.put("openCount", openCount);
            messagingTemplate.convertAndSendToUser(admin.getEmail(), "/queue/reports", ev);
            if (admin.getId().equals(reporter.getId())) continue;
            email(admin.getEmail(), "New report: " + reporter.getName() + " reported " + targetName,
                    "<p><b>" + esc(reporter.getName()) + "</b> reported <b>" + esc(targetName) + "</b> (" + esc(reason) + ").</p>"
                            + (r.getDetails() != null && !r.getDetails().isBlank() ? "<p>“" + esc(r.getDetails()) + "”</p>" : "")
                            + button(link, "Review in the Admin Panel"),
                    reporter.getName() + " reported " + targetName + " (" + reason + ").\nReview: " + link);
        }
    }

    /** Admin resolved or dismissed: tell the reporter, and refresh every admin's badge. */
    public void reviewed(Report r, String targetName, long openCount) {
        for (User admin : admins()) {
            Map<String, Object> ev = event("COUNT", r);
            ev.put("openCount", openCount);
            messagingTemplate.convertAndSendToUser(admin.getEmail(), "/queue/reports", ev);
        }
        if (r.getStatus() == Report.Status.OPEN) return; // reopened: nothing to tell the reporter
        userRepository.findById(r.getReporterId()).filter(User::isActive).ifPresent(reporter -> {
            boolean resolved = r.getStatus() == Report.Status.RESOLVED;
            String note = r.getResolutionNote() != null && !r.getResolutionNote().isBlank() ? r.getResolutionNote().trim() : null;
            Map<String, Object> ev = event("REVIEWED", r);
            ev.put("targetName", targetName);
            ev.put("status", r.getStatus().name());
            ev.put("note", note);
            messagingTemplate.convertAndSendToUser(reporter.getEmail(), "/queue/reports", ev);

            String outcome = resolved ? "reviewed it and took action" : "reviewed it and closed it without further action";
            email(reporter.getEmail(), "Your report about " + targetName + " was reviewed",
                    "<p>Hi " + esc(reporter.getName()) + ",</p><p>Thanks for your report about <b>" + esc(targetName)
                            + "</b>. An admin " + outcome + ".</p>"
                            + (note != null ? "<p><b>Admin's note:</b> " + esc(note) + "</p>" : "")
                            + button(base() + "/", "Open Office Chat"),
                    "Thanks for your report about " + targetName + ". An admin " + outcome + "."
                            + (note != null ? "\nAdmin's note: " + note : ""));
        });
    }

    private List<User> admins() {
        return userRepository.findByRoleAndActiveTrue(Role.ADMIN);
    }

    private Map<String, Object> event(String type, Report r) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("reportId", r.getId());
        return ev;
    }

    /** Sent in the background so clicking Resolve stays instant. */
    private void email(String to, String subject, String bodyHtml, String text) {
        if (!emailService.isEnabled()) return;
        String html = "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#0f172a\">"
                + "<h2 style=\"margin:0 0 12px\">Office Chat</h2>" + bodyHtml + "</div>";
        CompletableFuture.runAsync(() -> emailService.send(to, subject, html, text));
    }

    private static String button(String href, String label) {
        return "<p style=\"margin:20px 0\"><a href=\"" + href + "\" style=\"background:#4f46e5;color:#fff;padding:10px 18px;"
                + "border-radius:8px;text-decoration:none;font-weight:bold\">" + label + "</a></p>";
    }

    private String base() {
        return frontendUrl.replaceAll("/+$", "");
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
