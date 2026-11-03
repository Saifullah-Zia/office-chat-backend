package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.push.PushService;
import com.officechat.office_chat_backend.user.User;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes a closed or backgrounded app ring like a phone call: the "X is calling" push is re-sent every few
 * seconds (each one sounds and vibrates again) until the call is answered, declined, cancelled or times out.
 * Then a final push clears the ringing notification on every device, and turns it into "Missed call"
 * if nobody picked up. Also lets an app that was just opened find the call that's ringing for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRinger {

    private static final long RING_EVERY_SECONDS = 5;
    private static final int MAX_RINGS_DIRECT = 7;   // ~35s; the caller gives up after 30s
    private static final int MAX_RINGS_GROUP = 9;    // ~45s
    private static final int RING_TTL_SECONDS = 25;  // a ring delivered late (phone was offline) is useless
    private static final int MISSED_TTL_SECONDS = 6 * 3600;
    private static final int CLEAR_TTL_SECONDS = 120;

    private final PushService pushService;

    @Value("${jwt.secret}")
    private String secret;

    private record Ring(Long userId, Long conversationId, Long callId, Map<String, Object> payload, ScheduledFuture<?> task) {}

    /** conversationId:userId → ringing state. */
    private final Map<String, Ring> rings = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "call-ringer");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static String key(Long conversationId, Long userId) {
        return conversationId + ":" + userId;
    }

    /** Start ringing someone's devices. Replaces any ring already going for them in this chat. */
    public void start(Long toUserId, User from, Long conversationId, Long callId, boolean video, boolean group, String groupName) {
        String k = key(conversationId, toUserId);
        Ring old = rings.remove(k);
        if (old != null) old.task().cancel(false);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "call");
        p.put("title", video ? "🎥 Incoming video call" : "📞 Incoming voice call");
        p.put("body", group ? from.getName() + " is calling " + (groupName != null ? groupName : "the group") : from.getName() + " is calling you");
        p.put("tag", "call-" + conversationId);
        p.put("conversationId", conversationId);
        p.put("callId", callId);
        p.put("callType", video ? "VIDEO" : "AUDIO");
        p.put("fromUserId", from.getId());
        p.put("fromUserName", from.getName());
        p.put("toUserId", toUserId);
        p.put("isGroup", group);
        p.put("groupName", groupName);
        if (callId != null) p.put("declineToken", declineToken(callId, toUserId));

        int max = group ? MAX_RINGS_GROUP : MAX_RINGS_DIRECT;
        AtomicInteger count = new AtomicInteger();
        // The task is created before it's stored; the holder lets it look itself up safely.
        CompletableFuture<Ring> self = new CompletableFuture<>();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                int n = count.incrementAndGet();
                if (n > max) {
                    Ring me = self.getNow(null);
                    if (me != null && rings.remove(k, me)) finish(me, true);
                    throw new CancellationException(); // stops the repeating task
                }
                Map<String, Object> ring = new LinkedHashMap<>(p);
                ring.put("ring", n);
                pushService.pushToUser(toUserId, ring, RING_TTL_SECONDS);
                if (n == 1) pushService.pushToUserNative(toUserId, ring, RING_TTL_SECONDS);
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                log.debug("Ring push failed: {}", e.getMessage());
            }
        }, 0, RING_EVERY_SECONDS, TimeUnit.SECONDS);
        Ring ring = new Ring(toUserId, conversationId, callId, p, task);
        self.complete(ring);
        rings.put(k, ring);
    }

    /** They answered or declined: stop ringing and clear the notification on their other devices. */
    public void dismiss(Long conversationId, Long userId) {
        Ring r = rings.remove(key(conversationId, userId));
        if (r != null) finish(r, false);
    }

    /** The caller gave up before they answered: stop ringing and leave a "Missed call". */
    public void missed(Long conversationId, Long userId) {
        Ring r = rings.remove(key(conversationId, userId));
        if (r != null) finish(r, true);
    }

    /** The call is over: anyone still being rung for it missed it. */
    public void callEnded(Long callId) {
        if (callId == null) return;
        rings.forEach((k, r) -> {
            if (callId.equals(r.callId()) && rings.remove(k, r)) finish(r, true);
        });
    }

    /** The call ringing for this user right now (an app that was just opened shows it), or null. */
    public Map<String, Object> ringingFor(Long userId) {
        return rings.values().stream()
                .filter(r -> r.userId().equals(userId))
                .map(Ring::payload)
                .findFirst()
                .orElse(null);
    }

    private void finish(Ring r, boolean missed) {
        r.task().cancel(false);
        Map<String, Object> p = r.payload();
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("type", "call-cancel");
        clear.put("tag", p.get("tag"));
        clear.put("conversationId", r.conversationId());
        clear.put("missed", missed);
        if (missed) {
            boolean video = "VIDEO".equals(p.get("callType"));
            clear.put("title", video ? "🎥 Missed video call" : "📞 Missed call");
            clear.put("body", Boolean.TRUE.equals(p.get("isGroup"))
                    ? "From " + p.get("fromUserName") + " in " + p.get("groupName")
                    : "From " + p.get("fromUserName"));
        }
        try {
            pushService.pushToUser(r.userId(), clear, missed ? MISSED_TTL_SECONDS : CLEAR_TTL_SECONDS);
            pushService.pushToUserNative(r.userId(), clear, missed ? MISSED_TTL_SECONDS : CLEAR_TTL_SECONDS);
        } catch (Exception e) {
            log.debug("Ring clear push failed: {}", e.getMessage());
        }
    }

    // ── Decline straight from the notification (the service worker has no login token) ──

    public String declineToken(Long callId, Long userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(("decline:" + callId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isValidDeclineToken(Long callId, Long userId, String token) {
        if (token == null || callId == null || userId == null) return false;
        return MessageDigest.isEqual(declineToken(callId, userId).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
