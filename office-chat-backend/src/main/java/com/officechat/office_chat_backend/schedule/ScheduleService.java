package com.officechat.office_chat_backend.schedule;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageBroadcaster;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.message.MessageService;
import com.officechat.office_chat_backend.message.MessageType;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scheduled ("send later") messages and personal message reminders. */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final int MAX_PENDING_SCHEDULED = 50;
    private static final int MAX_PENDING_REMINDERS = 100;
    private static final Duration MAX_AHEAD = Duration.ofDays(365);

    private final ScheduledMessageRepository scheduledRepository;
    private final com.officechat.office_chat_backend.file.AttachmentRepository attachmentRepository;
    private final ReminderRepository reminderRepository;
    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final MessageBroadcaster broadcaster;
    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static void assertFutureTime(Instant when) {
        Instant now = Instant.now();
        if (when == null || when.isBefore(now.plusSeconds(30))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a time at least a minute from now");
        }
        if (when.isAfter(now.plus(MAX_AHEAD))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can schedule up to a year ahead");
        }
    }

    // ── Scheduled messages ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> schedule(User sender, Long conversationId, String content, Instant sendAt) {
        return schedule(sender, conversationId, content, sendAt, null);
    }

    /** With an already-uploaded file; it's attached when the message goes out. */
    @Transactional
    public Map<String, Object> schedule(User sender, Long conversationId, String content, Instant sendAt, Long attachmentId) {
        conversationService.assertMember(conversationId, sender.getId());
        assertFutureTime(sendAt);
        String text = content == null ? "" : content.trim();
        if (attachmentId != null) {
            var att = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
            if (!att.getUploadedBy().getId().equals(sender.getId()) || !att.getConversationId().equals(conversationId)
                    || att.getMessage() != null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This file can't be scheduled");
            }
        }
        if (text.isEmpty() && attachmentId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Write a message to schedule");
        if (text.length() > 10_000) throw new ApiException(HttpStatus.BAD_REQUEST, "Message is too long");
        if (scheduledRepository.countBySenderIdAndStatus(sender.getId(), ScheduledMessage.Status.PENDING) >= MAX_PENDING_SCHEDULED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You have too many scheduled messages");
        }

        ScheduledMessage saved = scheduledRepository.save(ScheduledMessage.builder()
                .senderId(sender.getId())
                .conversationId(conversationId)
                .content(text)
                .attachmentId(attachmentId)
                .sendAt(sendAt)
                .status(ScheduledMessage.Status.PENDING)
                .build());
        return view(saved);
    }

    public List<Map<String, Object>> listScheduled(Long userId, Long conversationId) {
        List<ScheduledMessage> list = conversationId == null
                ? scheduledRepository.findBySenderIdAndStatusOrderBySendAtAsc(userId, ScheduledMessage.Status.PENDING)
                : scheduledRepository.findBySenderIdAndConversationIdAndStatusOrderBySendAtAsc(
                        userId, conversationId, ScheduledMessage.Status.PENDING);
        return list.stream().map(this::view).toList();
    }

    @Transactional
    public void cancelScheduled(Long userId, Long id) {
        ScheduledMessage s = scheduledRepository.findById(id)
                .filter(x -> x.getSenderId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Scheduled message not found"));
        if (s.getStatus() != ScheduledMessage.Status.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This message was already sent");
        }
        s.setStatus(ScheduledMessage.Status.CANCELLED);
        scheduledRepository.save(s);
    }

    /** Sends everything that's due. Each message goes through the normal send path (permissions, blocks…). */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void sendDue() {
        for (ScheduledMessage s : scheduledRepository.findByStatusAndSendAtLessThanEqualOrderBySendAtAsc(
                ScheduledMessage.Status.PENDING, Instant.now())) {
            try {
                User sender = userRepository.findById(s.getSenderId())
                        .filter(User::isActive)
                        .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Sender account is inactive"));
                MessageResponse sent = messageService.send(s.getConversationId(), sender, s.getContent(),
                        s.getAttachmentId() == null ? MessageType.TEXT : null, s.getAttachmentId());
                broadcaster.broadcastNew(sent);
                s.setStatus(ScheduledMessage.Status.SENT);
                s.setSentMessageId(sent.id());
            } catch (ApiException e) {
                if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) continue; // retry on the next tick
                s.setStatus(ScheduledMessage.Status.FAILED);
                s.setFailureReason(e.getMessage());
            } catch (Exception e) {
                s.setStatus(ScheduledMessage.Status.FAILED);
                s.setFailureReason("Unexpected error");
                log.warn("Scheduled message {} failed: {}", s.getId(), e.getMessage());
            }
            scheduledRepository.save(s);
        }
    }

    private Map<String, Object> view(ScheduledMessage s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", s.getId());
        v.put("conversationId", s.getConversationId());
        v.put("content", s.getContent());
        if (s.getAttachmentId() != null) {
            attachmentRepository.findById(s.getAttachmentId())
                    .ifPresent(a -> v.put("attachmentName", a.getFileName()));
        }
        v.put("sendAt", s.getSendAt());
        v.put("status", s.getStatus().name());
        return v;
    }

    // ── Reminders ───────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> remind(User user, Long messageId, String note, Instant remindAt) {
        assertFutureTime(remindAt);
        if (reminderRepository.countByUserIdAndStatus(user.getId(), Reminder.Status.PENDING) >= MAX_PENDING_REMINDERS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You have too many reminders");
        }
        Reminder.ReminderBuilder b = Reminder.builder()
                .userId(user.getId())
                .remindAt(remindAt)
                .note(note != null && !note.isBlank() ? note.trim().substring(0, Math.min(note.trim().length(), 500)) : null)
                .status(Reminder.Status.PENDING);

        if (messageId != null) {
            Message m = messageRepository.findById(messageId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
            conversationService.assertMember(m.getConversation().getId(), user.getId());
            String content = m.getContent() == null ? "" : m.getContent();
            b.messageId(m.getId())
                    .conversationId(m.getConversation().getId())
                    .messageSender(m.getSender().getName())
                    .messageSnippet(content.length() > 480 ? content.substring(0, 480) + "…" : content);
        } else if (note == null || note.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add a note for the reminder");
        }
        return view(reminderRepository.save(b.build()));
    }

    /** Pending reminders plus fired ones the user hasn't dismissed yet. */
    public List<Map<String, Object>> listReminders(Long userId) {
        return reminderRepository.findByUserIdAndStatusInOrderByRemindAtAsc(
                        userId, List.of(Reminder.Status.PENDING, Reminder.Status.FIRED))
                .stream().map(this::view).toList();
    }

    @Transactional
    public void updateReminder(Long userId, Long id, String status) {
        Reminder r = reminderRepository.findById(id)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Reminder not found"));
        r.setStatus("DONE".equalsIgnoreCase(status) ? Reminder.Status.DONE : Reminder.Status.CANCELLED);
        reminderRepository.save(r);
    }

    @Transactional
    public Map<String, Object> snoozeReminder(Long userId, Long id, Instant until) {
        assertFutureTime(until);
        Reminder r = reminderRepository.findById(id)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Reminder not found"));
        r.setRemindAt(until);
        r.setStatus(Reminder.Status.PENDING);
        return view(reminderRepository.save(r));
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 25_000)
    public void fireDueReminders() {
        for (Reminder r : reminderRepository.findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(
                Reminder.Status.PENDING, Instant.now())) {
            r.setStatus(Reminder.Status.FIRED);
            reminderRepository.save(r);
            userRepository.findById(r.getUserId()).ifPresent(u ->
                    messagingTemplate.convertAndSendToUser(u.getEmail(), "/queue/reminders", view(r)));
        }
    }

    private Map<String, Object> view(Reminder r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.getId());
        v.put("messageId", r.getMessageId());
        v.put("conversationId", r.getConversationId());
        v.put("note", r.getNote());
        v.put("messageSnippet", r.getMessageSnippet());
        v.put("messageSender", r.getMessageSender());
        v.put("remindAt", r.getRemindAt());
        v.put("status", r.getStatus().name());
        return v;
    }
}
