package com.officechat.office_chat_backend.presence;

public record PresenceEvent(Long userId, String status) {
    public static PresenceEvent online(Long userId) {
        return new PresenceEvent(userId, "ONLINE");
    }
    public static PresenceEvent offline(Long userId) {
        return new PresenceEvent(userId, "OFFLINE");
    }
}