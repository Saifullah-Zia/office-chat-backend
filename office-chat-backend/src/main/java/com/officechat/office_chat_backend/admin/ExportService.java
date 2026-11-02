package com.officechat.office_chat_backend.admin;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationMember;
import com.officechat.office_chat_backend.conversation.ConversationMemberRepository;
import com.officechat.office_chat_backend.conversation.ConversationRepository;
import com.officechat.office_chat_backend.conversation.ConversationType;
import com.officechat.office_chat_backend.file.Attachment;
import com.officechat.office_chat_backend.file.AttachmentRepository;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compliance exports: a whole conversation (admins) or everything about one account (the user themself). */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final int PAGE = 500;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final AuditService auditService;

    public record CsvExport(String fileName, String csv) {}

    /**
     * Every conversation an admin can export, newest activity first, filtered by group name or
     * participant name/email. Direct chats are labelled by their two participants.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConversations(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Conversation> all = conversationRepository.findAll();
        Map<Long, List<ConversationMember>> members = memberRepository
                .findWithUsersByConversationIdIn(all.stream().map(Conversation::getId).toList()).stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId()));
        Map<Long, Message> latest = messageRepository.findLatestIn(all.stream().map(Conversation::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getConversation().getId(), m -> m, (a, b) -> a));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Conversation c : all) {
            List<ConversationMember> ms = members.getOrDefault(c.getId(), List.of());
            List<String> names = ms.stream().map(m -> m.getUser().getName()).sorted().toList();
            String label = c.getType() == ConversationType.GROUP
                    ? (c.getName() != null ? c.getName() : "Group")
                    : String.join(" ↔ ", names);
            String haystack = (label + " " + ms.stream()
                    .map(m -> m.getUser().getName() + " " + m.getUser().getEmail())
                    .collect(Collectors.joining(" "))).toLowerCase();
            if (!q.isEmpty() && !haystack.contains(q)) continue;

            Message last = latest.get(c.getId());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", c.getId());
            v.put("type", c.getType().name());
            v.put("label", label);
            v.put("memberCount", ms.size());
            v.put("lastMessageAt", last == null ? null : last.getCreatedAt());
            out.add(v);
        }
        out.sort(java.util.Comparator.comparing(
                (Map<String, Object> v) -> (java.time.Instant) v.get("lastMessageAt"),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return out.size() > 100 ? out.subList(0, 100) : out;
    }

    /** Every message in a conversation, oldest first, including deleted ones (marked as such). */
    @Transactional(readOnly = true)
    public CsvExport exportConversationCsv(Long conversationId, User admin) {
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        StringBuilder csv = new StringBuilder("﻿"); // BOM so Excel reads UTF-8 (emoji, accents)
        csv.append("message_id,sent_at_utc,sender_id,sender_name,sender_email,type,content,attachments,thread_root_id,edited_at_utc,deleted_at_utc\n");
        long after = 0;
        while (true) {
            List<Message> page = messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                    conversationId, after, PageRequest.of(0, PAGE));
            if (page.isEmpty()) break;
            Map<Long, String> files = attachmentNames(page);
            for (Message m : page) {
                csv.append(m.getId()).append(',')
                        .append(cell(m.getCreatedAt())).append(',')
                        .append(m.getSender().getId()).append(',')
                        .append(cell(m.getSender().getName())).append(',')
                        .append(cell(m.getSender().getEmail())).append(',')
                        .append(m.getType()).append(',')
                        .append(cell(m.getContent())).append(',')
                        .append(cell(files.get(m.getId()))).append(',')
                        .append(m.getThreadRootId() == null ? "" : m.getThreadRootId()).append(',')
                        .append(cell(m.getEditedAt())).append(',')
                        .append(cell(m.getDeletedAt())).append('\n');
            }
            after = page.get(page.size() - 1).getId();
        }

        auditService.log(admin.getId(), "CONVERSATION_EXPORTED", "CONVERSATION", conversationId,
                "Exported " + (c.getType() == ConversationType.GROUP ? "group \"" + c.getName() + "\"" : "direct chat") + " as CSV");
        String base = c.getType() == ConversationType.GROUP && c.getName() != null
                ? c.getName().replaceAll("[^A-Za-z0-9-_]+", "_") : "direct_chat";
        return new CsvExport("chat-" + conversationId + "-" + base + ".csv", csv.toString());
    }

    /** "Download my data": profile, chats the user is in, and every message they sent. */
    @Transactional(readOnly = true)
    public Map<String, Object> exportMyData(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", java.time.Instant.now().toString());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("name", user.getName());
        profile.put("email", user.getEmail());
        profile.put("role", user.getRole().name());
        profile.put("department", user.getDepartment());
        profile.put("workStatus", user.getWorkStatus());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("twoFactorEnabled", Boolean.TRUE.equals(user.getTotpEnabled()));
        out.put("profile", profile);

        List<Map<String, Object>> chats = new ArrayList<>();
        for (ConversationMember m : memberRepository.findWithConversationByUserId(user.getId())) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("conversationId", m.getConversation().getId());
            v.put("type", m.getConversation().getType().name());
            v.put("name", m.getConversation().getName());
            v.put("joinedAt", m.getJoinedAt());
            v.put("groupAdmin", m.isAdmin());
            chats.add(v);
        }
        out.put("conversations", chats);

        List<Map<String, Object>> messages = new ArrayList<>();
        long after = 0;
        while (true) {
            List<Message> page = messageRepository.findBySenderIdAndIdGreaterThanOrderByIdAsc(
                    user.getId(), after, PageRequest.of(0, PAGE));
            if (page.isEmpty()) break;
            Map<Long, String> files = attachmentNames(page);
            for (Message m : page) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", m.getId());
                v.put("conversationId", m.getConversation().getId());
                v.put("sentAt", m.getCreatedAt());
                v.put("type", m.getType().name());
                v.put("content", m.getDeletedAt() != null ? null : m.getContent());
                v.put("attachments", files.get(m.getId()));
                v.put("editedAt", m.getEditedAt());
                v.put("deletedAt", m.getDeletedAt());
                messages.add(v);
            }
            after = page.get(page.size() - 1).getId();
        }
        out.put("messages", messages);

        auditService.log(user.getId(), "PERSONAL_DATA_EXPORTED", "USER", user.getId(), messages.size() + " messages");
        return out;
    }

    private Map<Long, String> attachmentNames(List<Message> page) {
        return attachmentRepository.findByMessageIdIn(page.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId(),
                        Collectors.mapping(Attachment::getFileName, Collectors.joining("; "))));
    }

    /** RFC 4180 quoting; also defuses spreadsheet formulas (=, +, -, @) in user text. */
    private static String cell(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
