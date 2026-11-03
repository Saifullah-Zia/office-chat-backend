package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.push.PushService;
import com.officechat.office_chat_backend.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CallRingerTest {

    private record Sent(Long userId, Map<String, Object> payload, int ttl) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** Android-app (FCM) pushes, kept apart: the app gets one ring, not one every 5 seconds. */
    private final List<Sent> sentNative = new CopyOnWriteArrayList<>();
    private final PushService fakePush = new PushService(null, null, null, null, null, null, null) {
        @Override
        public void pushToUser(Long userId, Map<String, Object> payloadMap, int ttlSeconds) {
            sent.add(new Sent(userId, payloadMap, ttlSeconds));
        }

        @Override
        public void pushToUserNative(Long userId, Map<String, Object> payloadMap, int ttlSeconds) {
            sentNative.add(new Sent(userId, payloadMap, ttlSeconds));
        }
    };
    private final CallRinger ringer = new CallRinger(fakePush);

    {
        ReflectionTestUtils.setField(ringer, "secret", "test-secret");
    }

    @AfterEach
    void stop() {
        ringer.shutdown();
    }

    private static User caller() {
        User u = new User();
        u.setId(1L);
        u.setName("Ali");
        return u;
    }

    private void waitFor(int pushes) throws InterruptedException {
        for (int i = 0; i < 50 && sent.size() < pushes; i++) Thread.sleep(20);
    }

    @Test
    void ringsAtOnceAndIsFoundByTheOpenedApp() throws Exception {
        ringer.start(2L, caller(), 10L, 99L, false, false, null);
        waitFor(1);
        Sent first = sent.get(0);
        assertEquals(2L, first.userId());
        assertEquals("call", first.payload().get("type"));
        assertEquals("Ali is calling you", first.payload().get("body"));
        assertEquals("call-10", first.payload().get("tag"));
        assertTrue(first.ttl() <= 30, "a ring must not be delivered long after the call");

        Map<String, Object> ringing = ringer.ringingFor(2L);
        assertNotNull(ringing);
        assertEquals(99L, ringing.get("callId"));
        assertNull(ringer.ringingFor(3L));
    }

    @Test
    void answeringClearsTheNotificationWithoutMissedCall() throws Exception {
        ringer.start(2L, caller(), 10L, 99L, true, false, null);
        waitFor(1);
        ringer.dismiss(10L, 2L);
        Sent last = sent.get(sent.size() - 1);
        assertEquals("call-cancel", last.payload().get("type"));
        assertEquals(false, last.payload().get("missed"));
        assertNull(ringer.ringingFor(2L));

        int before = sent.size();
        Thread.sleep(200);
        assertEquals(before, sent.size(), "no more rings after answering");

        // The Android app got exactly one ring and one cancel.
        assertEquals(2, sentNative.size());
        assertEquals("call", sentNative.get(0).payload().get("type"));
        assertEquals("call-cancel", sentNative.get(1).payload().get("type"));
    }

    @Test
    void callerGivingUpLeavesMissedCall() throws Exception {
        ringer.start(2L, caller(), 10L, 99L, true, false, null);
        waitFor(1);
        ringer.missed(10L, 2L);
        Sent last = sent.get(sent.size() - 1);
        assertEquals(true, last.payload().get("missed"));
        assertEquals("🎥 Missed video call", last.payload().get("title"));
        assertEquals("From Ali", last.payload().get("body"));
    }

    @Test
    void endingTheCallStopsEveryoneStillRinging() throws Exception {
        ringer.start(2L, caller(), 10L, 99L, false, true, "Billing");
        ringer.start(3L, caller(), 10L, 99L, false, true, "Billing");
        waitFor(2);
        ringer.dismiss(10L, 3L); // 3 joined
        ringer.callEnded(99L);
        assertNull(ringer.ringingFor(2L));
        long missed = sent.stream().filter(s -> Boolean.TRUE.equals(s.payload().get("missed"))).count();
        assertEquals(1, missed, "only the one who never joined missed it");
    }

    @Test
    void declineTokenOnlyWorksForThatCallAndPerson() {
        String token = ringer.declineToken(99L, 2L);
        assertTrue(ringer.isValidDeclineToken(99L, 2L, token));
        assertFalse(ringer.isValidDeclineToken(98L, 2L, token));
        assertFalse(ringer.isValidDeclineToken(99L, 3L, token));
        assertFalse(ringer.isValidDeclineToken(99L, 2L, "forged"));
        assertFalse(ringer.isValidDeclineToken(99L, 2L, null));
    }
}
