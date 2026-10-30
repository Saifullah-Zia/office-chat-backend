package com.officechat.office_chat_backend.task;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.common.RateLimiter;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RateLimiter rateLimiter;

    @Transactional
    public Map<String, Object> create(User creator, String title, String notes, Long conversationId,
                                      Long messageId, Long assigneeId, Instant dueAt) {
        rateLimiter.check("task:" + creator.getId(), 30, Duration.ofMinutes(1), "You're creating tasks too quickly.");

        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Give the task a title");
        if (t.length() > 300) t = t.substring(0, 300);

        if (messageId != null) {
            Message m = messageRepository.findById(messageId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
            conversationId = m.getConversation().getId();
        }
        Long assignee = assigneeId != null ? assigneeId : creator.getId();
        userRepository.findById(assignee)
                .filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignee not found"));

        if (conversationId != null) {
            conversationService.assertMember(conversationId, creator.getId());
            if (!assignee.equals(creator.getId())) {
                try {
                    conversationService.assertMember(conversationId, assignee);
                } catch (ApiException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "The assignee isn't in this chat");
                }
            }
        }

        Task task = taskRepository.save(Task.builder()
                .title(t)
                .notes(notes != null && !notes.isBlank() ? notes.trim() : null)
                .conversationId(conversationId)
                .messageId(messageId)
                .createdBy(creator.getId())
                .assigneeId(assignee)
                .dueAt(dueAt)
                .status(Task.Status.OPEN)
                .build());

        Map<String, Object> view = view(task);
        if (!assignee.equals(creator.getId())) notifyAssignee(task, view);
        return view;
    }

    /** scope = assigned (default) | created | conversation */
    public List<Map<String, Object>> list(Long userId, String scope, Long conversationId) {
        List<Task> tasks;
        if ("created".equalsIgnoreCase(scope)) {
            tasks = taskRepository.findByCreatedByOrderByCreatedAtDesc(userId);
        } else if ("conversation".equalsIgnoreCase(scope)) {
            if (conversationId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "conversationId is required");
            conversationService.assertMember(conversationId, userId);
            tasks = taskRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
        } else {
            tasks = taskRepository.findByAssigneeIdOrderByCreatedAtDesc(userId);
        }
        // Open first (soonest due first), then done (most recently completed first).
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator
                .comparing((Task x) -> x.getStatus() == Task.Status.DONE)
                .thenComparing(x -> x.getStatus() == Task.Status.DONE
                        ? Instant.MAX.minusSeconds(x.getCompletedAt() != null ? x.getCompletedAt().getEpochSecond() : 0)
                        : (x.getDueAt() != null ? x.getDueAt() : Instant.MAX)));
        return sorted.stream().map(this::view).toList();
    }

    public long countOpenAssigned(Long userId) {
        return taskRepository.countByAssigneeIdAndStatus(userId, Task.Status.OPEN);
    }

    /** Creator or assignee may update. Body fields are optional. */
    @Transactional
    public Map<String, Object> update(User user, Long id, Map<String, Object> body) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        boolean isCreator = task.getCreatedBy().equals(user.getId());
        boolean isAssignee = task.getAssigneeId().equals(user.getId());
        if (!isCreator && !isAssignee) throw new ApiException(HttpStatus.FORBIDDEN, "You can't change this task");

        if (body.containsKey("status")) {
            boolean done = "DONE".equalsIgnoreCase(String.valueOf(body.get("status")));
            task.setStatus(done ? Task.Status.DONE : Task.Status.OPEN);
            task.setCompletedAt(done ? Instant.now() : null);
        }
        if (body.containsKey("title") && isCreator) {
            String t = String.valueOf(body.get("title")).trim();
            if (t.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Give the task a title");
            task.setTitle(t.length() > 300 ? t.substring(0, 300) : t);
        }
        if (body.containsKey("notes") && isCreator) {
            Object n = body.get("notes");
            task.setNotes(n == null || n.toString().isBlank() ? null : n.toString().trim());
        }
        if (body.containsKey("dueAt") && isCreator) {
            Object d = body.get("dueAt");
            try {
                task.setDueAt(d == null || d.toString().isBlank() ? null : Instant.parse(d.toString()));
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid due date");
            }
        }
        return view(taskRepository.save(task));
    }

    @Transactional
    public void delete(User user, Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!task.getCreatedBy().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the person who created a task can delete it");
        }
        taskRepository.delete(task);
    }

    private void notifyAssignee(Task task, Map<String, Object> view) {
        userRepository.findById(task.getAssigneeId()).ifPresent(u ->
                messagingTemplate.convertAndSendToUser(u.getEmail(), "/queue/tasks", view));
    }

    private String nameOf(Long id) {
        return userRepository.findById(id).map(User::getName).orElse("User #" + id);
    }

    private Map<String, Object> view(Task t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", t.getId());
        v.put("title", t.getTitle());
        v.put("notes", t.getNotes());
        v.put("conversationId", t.getConversationId());
        v.put("messageId", t.getMessageId());
        v.put("createdBy", t.getCreatedBy());
        v.put("createdByName", nameOf(t.getCreatedBy()));
        v.put("assigneeId", t.getAssigneeId());
        v.put("assigneeName", nameOf(t.getAssigneeId()));
        v.put("dueAt", t.getDueAt());
        v.put("status", t.getStatus().name());
        v.put("createdAt", t.getCreatedAt());
        v.put("completedAt", t.getCompletedAt());
        return v;
    }
}
