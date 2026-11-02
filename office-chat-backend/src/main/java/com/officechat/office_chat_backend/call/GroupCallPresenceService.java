package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.conversation.ConversationType;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in each running group call, so members who missed the ring can join late.
 * Clients check in when they join and every minute after; anyone silent for STALE_AFTER is dropped
 * (closed tab, crash). When the last person leaves, the call is closed in the call log.
 * In-memory: fine for a single server; a multi-server setup would keep this in Redis.
 */
@Service
@RequiredArgsConstructor
public class GroupCallPresenceService {

    private static final Duration STALE_AFTER = Duration.ofSeconds(150);

    private final CallRepository callRepository;
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;

    private record Participant(String name, Instant lastSeen) {}

    private static final class Live {
        final Long callId;
        final String type;
        final Instant startedAt;
        final Map<Long, Participant> participants = new ConcurrentHashMap<>();

        Live(Long callId, String type, Instant startedAt) {
            this.callId = callId;
            this.type = type;
            this.startedAt = startedAt;
        }
    }

    /** conversationId -> running call */
    private final Map<Long, Live> live = new ConcurrentHashMap<>();

    /** Joined, or still here (heartbeat). */
    @Transactional
    public Map<String, Object> checkIn(Long callId, User user) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Call not found"));
        Long conversationId = call.getConversation().getId();
        if (call.getConversation().getType() != ConversationType.GROUP) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only group calls can be joined later");
        }
        conversationService.assertMember(conversationId, user.getId());
        if (call.getEndedAt() != null && !live.containsKey(conversationId)) {
            throw new ApiException(HttpStatus.GONE, "This call has ended");
        }

        Live current = live.computeIfAbsent(conversationId,
                k -> new Live(callId, call.getType().name(), call.getStartedAt() != null ? call.getStartedAt() : Instant.now()));
        boolean isNew = current.participants.put(user.getId(), new Participant(user.getName(), Instant.now())) == null;

        // A second person in the call means it was answered.
        if (current.participants.size() > 1 && call.getAnsweredAt() == null) {
            call.setAnsweredAt(Instant.now());
            call.setStatus(CallStatus.ONGOING);
            callRepository.save(call);
        }
        if (isNew) broadcast(conversationId);
        return view(current);
    }

    @Transactional
    public void leave(Long callId, User user) {
        live.forEach((conversationId, l) -> {
            if (l.callId.equals(callId) && l.participants.remove(user.getId()) != null) settle(conversationId, l);
        });
    }

    /** The running call in a chat, or {active:false}. Members only. */
    public Map<String, Object> active(Long conversationId, Long userId) {
        conversationService.assertMember(conversationId, userId);
        Live l = live.get(conversationId);
        return l == null || l.participants.isEmpty() ? Map.of("active", false) : view(l);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void dropSilentParticipants() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        live.forEach((conversationId, l) -> {
            if (l.participants.values().removeIf(p -> p.lastSeen().isBefore(cutoff))) settle(conversationId, l);
        });
    }

    /** After someone left: announce it, and close the call once it's empty. */
    private void settle(Long conversationId, Live l) {
        if (l.participants.isEmpty()) {
            live.remove(conversationId, l);
            callRepository.findById(l.callId).filter(c -> c.getEndedAt() == null).ifPresent(c -> {
                c.setEndedAt(Instant.now());
                c.setStatus(c.getAnsweredAt() != null ? CallStatus.ENDED : CallStatus.MISSED);
                callRepository.save(c);
            });
        }
        broadcast(conversationId);
    }

    private void broadcast(Long conversationId) {
        Live l = live.get(conversationId);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId + ".call",
                (Object) (l == null || l.participants.isEmpty() ? Map.of("active", false) : view(l)));
    }

    private static Map<String, Object> view(Live l) {
        List<Map<String, Object>> people = new ArrayList<>();
        l.participants.forEach((id, p) -> people.add(Map.of("userId", id, "name", p.name())));
        people.sort(Comparator.comparing(p -> (String) p.get("name")));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("active", true);
        v.put("callId", l.callId);
        v.put("type", l.type);
        v.put("startedAt", l.startedAt);
        v.put("participants", people);
        return v;
    }
}
