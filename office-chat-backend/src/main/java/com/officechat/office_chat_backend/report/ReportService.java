package com.officechat.office_chat_backend.report;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportService {

    public static final Set<String> REASONS = Set.of(
            "SPAM", "HARASSMENT", "INAPPROPRIATE", "IMPERSONATION", "SECURITY", "OTHER");

    private final ReportRepository reportRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationService conversationService;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    @Transactional
    public Map<String, Object> create(User reporter, Long targetUserId, Long messageId, String reason, String details) {
        rateLimiter.check("report:" + reporter.getId(), 10, Duration.ofHours(1),
                "You've sent a lot of reports recently. Please try again later.");

        String normalizedReason = reason == null ? "" : reason.trim().toUpperCase();
        if (!REASONS.contains(normalizedReason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a reason for the report");
        }
        if (details != null && details.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Details can be at most 2000 characters");
        }

        Long conversationId = null;
        String snapshot = null;
        Long target = targetUserId;

        if (messageId != null) {
            Message message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
            conversationId = message.getConversation().getId();
            conversationService.assertMember(conversationId, reporter.getId());
            target = message.getSender().getId();
            snapshot = message.getContent();
            if (reportRepository.existsByReporterIdAndMessageIdAndStatus(reporter.getId(), messageId, Report.Status.OPEN)) {
                throw new ApiException(HttpStatus.CONFLICT, "You've already reported this message");
            }
        }

        if (target == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Say who or what you're reporting");
        }
        if (target.equals(reporter.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't report yourself");
        }
        userRepository.findById(target)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        Report report = reportRepository.save(Report.builder()
                .reporterId(reporter.getId())
                .targetUserId(target)
                .messageId(messageId)
                .conversationId(conversationId)
                .messageSnapshot(snapshot)
                .reason(normalizedReason)
                .details(details != null ? details.trim() : null)
                .status(Report.Status.OPEN)
                .build());

        return toView(report);
    }

    public List<Map<String, Object>> list(String status) {
        List<Report> reports = status == null || status.isBlank() || status.equalsIgnoreCase("ALL")
                ? reportRepository.findAllByOrderByCreatedAtDesc()
                : reportRepository.findByStatusOrderByCreatedAtDesc(Report.Status.valueOf(status.toUpperCase()));
        return reports.stream().map(this::toView).toList();
    }

    public long countOpen() {
        return reportRepository.countByStatus(Report.Status.OPEN);
    }

    @Transactional
    public Map<String, Object> resolve(Long reportId, User admin, String status, String note) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Report not found"));
        Report.Status next;
        try {
            next = Report.Status.valueOf(status == null ? "" : status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Status must be OPEN, RESOLVED or DISMISSED");
        }

        report.setStatus(next);
        report.setResolutionNote(note);
        report.setResolvedBy(next == Report.Status.OPEN ? null : admin.getId());
        report.setResolvedAt(next == Report.Status.OPEN ? null : Instant.now());
        reportRepository.save(report);

        auditService.log(admin.getId(), "REPORT_" + next.name(), "REPORT", report.getId(),
                "Report #" + report.getId() + " against " + nameOf(report.getTargetUserId())
                        + (note != null && !note.isBlank() ? " — " + note : ""));
        return toView(report);
    }

    private String nameOf(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getName).orElse("User #" + userId);
    }

    private Map<String, Object> toView(Report r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.getId());
        v.put("reporterId", r.getReporterId());
        v.put("reporterName", nameOf(r.getReporterId()));
        v.put("targetUserId", r.getTargetUserId());
        v.put("targetUserName", nameOf(r.getTargetUserId()));
        v.put("messageId", r.getMessageId());
        v.put("conversationId", r.getConversationId());
        v.put("messageSnapshot", r.getMessageSnapshot());
        v.put("reason", r.getReason());
        v.put("details", r.getDetails());
        v.put("status", r.getStatus().name());
        v.put("resolvedByName", nameOf(r.getResolvedBy()));
        v.put("resolutionNote", r.getResolutionNote());
        v.put("createdAt", r.getCreatedAt());
        v.put("resolvedAt", r.getResolvedAt());
        return v;
    }
}
