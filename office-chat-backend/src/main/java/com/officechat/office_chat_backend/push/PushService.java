package com.officechat.office_chat_backend.push;

import tools.jackson.databind.ObjectMapper;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationMember;
import com.officechat.office_chat_backend.conversation.ConversationMemberRepository;
import com.officechat.office_chat_backend.conversation.ConversationType;
import com.officechat.office_chat_backend.message.MessageType;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.presence.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Notifies members who are offline (app closed) about new messages, via Web Push. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private static final int MAX_SUBSCRIPTIONS_PER_USER = 10;

    private final PushSubscriptionRepository repository;
    private final ConversationMemberRepository memberRepository;
    private final PresenceService presenceService;
    private final WebPushSender sender;
    private final ObjectMapper objectMapper;
    private final NativePushTokenRepository nativeRepository;
    private final FcmSender fcmSender;

    @Transactional
    public void subscribe(Long userId, String endpoint, String p256dh, String auth) {
        if (endpoint == null || p256dh == null || auth == null || !WebPushSender.isAllowedEndpoint(endpoint)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported push subscription");
        }
        PushSubscription sub = repository.findByEndpoint(endpoint).orElse(null);
        if (sub == null) {
            if (repository.countByUserId(userId) >= MAX_SUBSCRIPTIONS_PER_USER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Too many devices are subscribed to notifications");
            }
            sub = PushSubscription.builder().endpoint(endpoint).build();
        }
        // A browser re-subscribed under another account now belongs to that account.
        sub.setUserId(userId);
        sub.setP256dh(p256dh);
        sub.setAuth(auth);
        repository.save(sub);
    }

    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        repository.findByEndpoint(endpoint)
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(repository::delete);
    }

    /** Called after a new message is broadcast. Never throws; push is best-effort. */
    @Transactional(readOnly = true)
    public void notifyNewMessage(MessageResponse message) {
        try {
            List<ConversationMember> members = memberRepository.findWithUsersByConversationIdIn(List.of(message.conversationId()));
            Instant now = Instant.now();
            List<Long> recipients = members.stream()
                    .filter(m -> !m.getUser().getId().equals(message.senderId()))
                    .filter(m -> m.getMutedUntil() == null || m.getMutedUntil().isBefore(now))
                    .map(m -> m.getUser().getId())
                    // Notify every device unless they're looking at the app right now (each device's
                    // service worker also stays quiet while the app is on its screen).
                    .filter(id -> !presenceService.isViewing(id))
                    .toList();
            if (recipients.isEmpty()) return;
            Map<String, Object> p = payload(message, members.get(0).getConversation());
            sendTo(recipients, p);
            Map<String, Object> nativeMsg = new LinkedHashMap<>(p);
            nativeMsg.put("type", "message");
            sendNative(recipients, nativeMsg, 86400);
        } catch (Exception e) {
            log.warn("Push notification failed for message {}: {}", message.id(), e.getMessage());
        }
    }

    /**
     * Push to all of one person's devices regardless of whether they look online (used for calls, see
     * CallRinger: a phone whose app was just closed can still look connected but can't ring).
     * The service worker skips it when the app is open on screen.
     */
    public void pushToUser(Long userId, Map<String, Object> payloadMap, int ttlSeconds) throws Exception {
        sendTo(List.of(userId), payloadMap, ttlSeconds);
    }

    /** To the person's Android app(s) (FCM). Used once per call for ringing: the app rings by itself. */
    public void pushToUserNative(Long userId, Map<String, Object> payloadMap, int ttlSeconds) {
        sendNative(List.of(userId), payloadMap, ttlSeconds);
    }

    // ── Android app (FCM) ───────────────────────────────────────────────

    @Transactional
    public void registerNative(Long userId, String token, String platform) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid device token");
        }
        NativePushToken t = nativeRepository.findByToken(token).orElse(null);
        if (t == null) {
            if (nativeRepository.countByUserId(userId) >= MAX_SUBSCRIPTIONS_PER_USER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Too many devices are registered for notifications");
            }
            t = NativePushToken.builder().token(token).build();
        }
        // A phone signed in to another account now belongs to that account.
        t.setUserId(userId);
        t.setPlatform(platform == null || platform.isBlank() ? "android" : platform.trim().toLowerCase());
        t.setUpdatedAt(Instant.now());
        nativeRepository.save(t);
    }

    @Transactional
    public void unregisterNative(Long userId, String token) {
        nativeRepository.findByToken(token).filter(t -> t.getUserId().equals(userId)).ifPresent(nativeRepository::delete);
    }

    /**
     * "Is my phone reachable when the app is closed?" Sends a ringing test to the person's Android app(s)
     * after a delay, giving them time to close the app first. Returns how many phones it will go to.
     */
    public int sendNativeTest(Long userId, int delaySeconds) {
        int phones = nativeRepository.findByUserIdIn(List.of(userId)).size();
        if (!fcmSender.isEnabled() || phones == 0) return 0;
        int delay = Math.max(0, Math.min(delaySeconds, 120));
        java.util.concurrent.CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            Map<String, Object> test = new LinkedHashMap<>();
            test.put("type", "test");
            test.put("title", "✅ Office Chat can reach this phone");
            test.put("body", "Calls will ring even when the app is closed.");
            sendNative(List.of(userId), test, 120);
        });
        return phones;
    }

    private void sendNative(List<Long> userIds, Map<String, Object> data, int ttlSeconds) {
        if (!fcmSender.isEnabled()) return;
        Object type = data.get("type");
        // Calls are logged at INFO: "did the ring reach Google?" is the first question when a phone doesn't ring.
        boolean important = "call".equals(type) || "call-cancel".equals(type) || "test".equals(type);
        // "Stop ringing" after an answer shows nothing on the phone: normal priority (see FcmSender).
        boolean high = !("call-cancel".equals(type) && !Boolean.TRUE.equals(data.get("missed")));
        for (NativePushToken t : nativeRepository.findByUserIdIn(userIds)) {
            fcmSender.send(t.getToken(), data, ttlSeconds, high).whenComplete((status, err) -> {
                if (err != null) {
                    log.warn("FCM {} to user {} phone #{} failed: {}", type, t.getUserId(), t.getId(), err.getMessage());
                    return;
                }
                if (important) log.info("FCM {} to user {} phone #{}: {}", type, t.getUserId(), t.getId(),
                        status == 200 ? "delivered to Google (200)" : "status " + status);
                if (status == 404) nativeRepository.deleteById(t.getId()); // app uninstalled or signed out
            });
        }
    }

    private void sendTo(List<Long> userIds, Map<String, Object> payloadMap) throws Exception {
        sendTo(userIds, payloadMap, 86400);
    }

    private void sendTo(List<Long> userIds, Map<String, Object> payloadMap, int ttlSeconds) throws Exception {
        List<PushSubscription> subs = repository.findByUserIdIn(userIds);
        if (subs.isEmpty()) return;
        byte[] payload = objectMapper.writeValueAsString(payloadMap).getBytes(StandardCharsets.UTF_8);
        for (PushSubscription sub : subs) {
            sender.send(sub, payload, ttlSeconds).whenComplete((status, err) -> {
                if (err != null) {
                    log.debug("Push to subscription {} failed: {}", sub.getId(), err.getMessage());
                } else if (status == 404 || status == 410) {
                    repository.deleteById(sub.getId()); // browser unsubscribed or expired
                } else if (status >= 400) {
                    log.debug("Push service returned {} for subscription {}", status, sub.getId());
                }
            });
        }
    }

    private Map<String, Object> payload(MessageResponse m, Conversation conv) {
        boolean group = conv.getType() == ConversationType.GROUP;
        String body;
        if (m.type() == MessageType.IMAGE) body = "📷 Photo";
        else if (m.type() == MessageType.VOICE || m.type() == MessageType.AUDIO) body = "🎤 Voice note";
        else if (m.type() == MessageType.FILE) body = "📁 File";
        else if (m.type() == MessageType.POLL) body = "📊 Poll";
        else body = m.content() == null ? "" : m.content();
        if (body.length() > 140) body = body.substring(0, 140) + "…";

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", group ? (conv.getName() != null ? conv.getName() : "Group") : m.senderName());
        p.put("body", group ? m.senderName() + ": " + body : body);
        p.put("conversationId", m.conversationId());
        p.put("messageId", m.id());
        p.put("tag", "conv-" + m.conversationId());
        return p;
    }
}
