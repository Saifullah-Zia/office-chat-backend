package com.officechat.office_chat_backend.presence;

import java.time.Instant;

public record PresenceEvent(Long userId, String status, Instant lastSeenAt) {
    public static PresenceEvent online(Long userId) {
        return new PresenceEvent(userId, "ONLINE", null);
    }
    public static PresenceEvent offline(Long userId, Instant lastSeenAt) {
        return new PresenceEvent(userId, "OFFLINE", lastSeenAt);
    }
}
