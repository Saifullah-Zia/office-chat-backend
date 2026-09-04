package com.officechat.office_chat_backend.presence;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PresenceService {

    private final Map<Long, Integer> sessionCounts = new ConcurrentHashMap<>();

    // Returns true only when this is the user's FIRST active session (i.e. they just came online)
    public synchronized boolean userConnected(Long userId) {
        int count = sessionCounts.merge(userId, 1, Integer::sum);
        return count == 1;
    }

    // Returns true only when the user's LAST session just closed (i.e. they just went offline)
    public synchronized boolean userDisconnected(Long userId) {
        Integer count = sessionCounts.computeIfPresent(userId, (k, v) -> v - 1);
        if (count != null && count <= 0) {
            sessionCounts.remove(userId);
            return true;
        }
        return false;
    }

    public boolean isOnline(Long userId) {
        return sessionCounts.containsKey(userId);
    }

    public Set<Long> getOnlineUserIds() {
        return sessionCounts.keySet();
    }
}