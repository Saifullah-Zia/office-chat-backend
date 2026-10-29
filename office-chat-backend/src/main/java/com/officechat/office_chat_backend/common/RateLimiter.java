package com.officechat.office_chat_backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter keyed by an arbitrary string (e.g. "msg:{userId}").
 * Good for a single server instance; a cluster would need a shared store such as Redis.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Records one hit for {@code key}; throws 429 if more than {@code limit} hits fall within {@code window}. */
    public void check(String key, int limit, Duration window, String message) {
        if (!tryAcquire(key, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Clears the counter for a key (e.g. after a successful login). */
    public void reset(String key) {
        hits.remove(key);
    }

    /** Drops idle keys so the map doesn't grow forever. Windows used here are at most 1 hour. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || last < cutoff;
            }
        });
    }
}
