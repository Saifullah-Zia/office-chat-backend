package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationMember;
import com.officechat.office_chat_backend.conversation.ConversationMemberRepository;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Edit history of a message, and acknowledgements in announcement groups. */
@Service
@RequiredArgsConstructor
public class MessageMetaService {

    private final MessageRepository messageRepository;
    private final MessageEditRepository editRepository;
    private final MessageAckRepository ackRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;

    /** Every version, oldest first; the last entry is the current text. Members only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> editHistory(Long messageId, Long userId) {
        Message m = visibleMessage(messageId, userId);
        if (m.getDeletedAt() != null) throw new ApiException(HttpStatus.NOT_FOUND, "Message not found");
        List<Map<String, Object>> versions = new ArrayList<>();
        Instant from = m.getCreatedAt();
        for (MessageEdit e : editRepository.findByMessageIdOrderByEditedAtAsc(messageId)) {
            versions.add(Map.of("content", e.getPreviousContent(), "since", from, "current", false));
            from = e.getEditedAt();
        }
        versions.add(Map.of("content", m.getContent(), "since", from, "current", true));
        return versions;
    }

    // ── Acknowledgements ────────────────────────────────────────────────

    /** In announcement groups every post (not thread replies) can be acknowledged by the other members. */
    private Message ackable(Long messageId, Long userId) {
        Message m = visibleMessage(messageId, userId);
        if (!m.getConversation().isAnnouncement() || m.getThreadRootId() != null || m.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This message doesn't take acknowledgements");
        }
        return m;
    }

    @Transactional
    public Map<String, Object> acknowledge(Long messageId, User user) {
        Message m = ackable(messageId, user.getId());
        if (m.getSender().getId().equals(user.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't acknowledge your own announcement");
        }
        Long conversationId = m.getConversation().getId();
        if (!ackRepository.existsByMessageIdAndUserId(messageId, user.getId())) {
            ackRepository.save(MessageAck.builder().messageId(messageId).conversationId(conversationId)
                    .userId(user.getId()).ackedAt(Instant.now()).build());
        }
        long count = ackRepository.countByMessageId(messageId);
        // Small live event so open chats update the "n of m" count without a refetch.
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId + ".ack",
                (Object) Map.of("messageId", messageId, "count", count, "userId", user.getId()));
        return Map.of("messageId", messageId, "count", count);
    }

    /** For the chat view: counts per message, which ones I acknowledged, and how many could. */
    @Transactional(readOnly = true)
    public Map<String, Object> conversationAcks(Long conversationId, Long userId) {
        conversationService.assertMember(conversationId, userId);
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : ackRepository.countsByConversation(conversationId)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return Map.of(
                "counts", counts,
                "mine", ackRepository.myAckedMessageIds(conversationId, userId),
                // Everyone except the poster can acknowledge.
                "audience", Math.max(0, memberRepository.findByConversationId(conversationId).size() - 1));
    }

    /** Who acknowledged and who hasn't yet. The poster and chat admins only. */
    @Transactional(readOnly = true)
    public Map<String, Object> ackDetails(Long messageId, Long userId) {
        Message m = ackable(messageId, userId);
        Conversation c = m.getConversation();
        if (!m.getSender().getId().equals(userId) && !conversationService.isConversationAdmin(c.getId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the poster or an admin can see this");
        }
        Map<Long, Instant> ackedAt = new HashMap<>();
        ackRepository.findByMessageIdOrderByAckedAtAsc(messageId).forEach(a -> ackedAt.put(a.getUserId(), a.getAckedAt()));

        List<Map<String, Object>> acknowledged = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        for (ConversationMember member : memberRepository.findWithUsersByConversationIdIn(List.of(c.getId()))) {
            User u = member.getUser();
            if (u.getId().equals(m.getSender().getId())) continue;
            Instant at = ackedAt.get(u.getId());
            if (at != null) {
                acknowledged.add(Map.of("userId", u.getId(), "name", u.getName(), "at", at));
            } else {
                pending.add(Map.of("userId", u.getId(), "name", u.getName()));
            }
        }
        acknowledged.sort(Comparator.comparing(v -> (Instant) v.get("at")));
        pending.sort(Comparator.comparing(v -> (String) v.get("name")));
        return Map.of("acknowledged", acknowledged, "pending", pending);
    }

    private Message visibleMessage(Long messageId, Long userId) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
        conversationService.assertMember(m.getConversation().getId(), userId);
        return m;
    }
}
