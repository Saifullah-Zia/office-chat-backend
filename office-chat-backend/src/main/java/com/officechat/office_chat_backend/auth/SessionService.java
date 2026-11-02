package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final UserSessionRepository repository;
    private final LoginAlertService loginAlertService;

    @Value("${jwt.expiration}")
    private Long expirationMs;

    /** sid → (active?, checkedAt). Short cache so every request doesn't hit the DB; revocations apply within seconds. */
    private record CacheEntry(boolean active, long checkedAt) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_MS = 15_000;
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(2);

    @Transactional
    public UserSession create(User user, HttpServletRequest request) {
        Instant now = Instant.now();
        String ua = request != null ? request.getHeader("User-Agent") : null;
        String ip = request != null ? clientIp(request) : null;
        String deviceHash = request != null ? hashDevice(request.getHeader("X-Device-Id")) : null;

        // Alert only once this account has a known device to compare with; the first device
        // (and anyone who last signed in before device ids existed) is learnt silently.
        if (deviceHash != null && repository.existsByUserIdAndDeviceHashIsNotNull(user.getId())
                && !repository.existsByUserIdAndDeviceHash(user.getId(), deviceHash)) {
            loginAlertService.newDevice(user, ua, now);
        }

        return repository.save(UserSession.builder()
                .id(UUID.randomUUID().toString())
                .userId(user.getId())
                .userAgent(ua != null && ua.length() > 400 ? ua.substring(0, 400) : ua)
                .ipAddress(ip)
                .deviceHash(deviceHash)
                .createdAt(now)
                .lastActiveAt(now)
                .expiresAt(now.plusMillis(expirationMs))
                .build());
    }

    private static String hashDevice(String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9-]{16,64}")) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** True if the session exists, isn't revoked and hasn't expired. Also bumps last-active occasionally. */
    @Transactional
    public boolean isActive(String sid) {
        if (sid == null) return true; // tokens issued before sessions existed
        long nowMs = System.currentTimeMillis();
        CacheEntry hit = cache.get(sid);
        if (hit != null && nowMs - hit.checkedAt() < CACHE_MS) return hit.active();

        Optional<UserSession> s = repository.findById(sid);
        Instant now = Instant.now();
        boolean active = s.isPresent() && s.get().getRevokedAt() == null && s.get().getExpiresAt().isAfter(now);
        if (active) {
            UserSession session = s.get();
            if (session.getLastActiveAt() == null || session.getLastActiveAt().plus(TOUCH_EVERY).isBefore(now)) {
                session.setLastActiveAt(now);
                repository.save(session);
            }
        }
        cache.put(sid, new CacheEntry(active, nowMs));
        if (cache.size() > 10_000) cache.clear();
        return active;
    }

    public List<Map<String, Object>> list(Long userId, String currentSid) {
        return repository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastActiveAtDesc(userId, Instant.now())
                .stream()
                .map(s -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", s.getId());
                    v.put("userAgent", s.getUserAgent());
                    v.put("ipAddress", s.getIpAddress());
                    v.put("createdAt", s.getCreatedAt());
                    v.put("lastActiveAt", s.getLastActiveAt());
                    v.put("current", s.getId().equals(currentSid));
                    return v;
                })
                .toList();
    }

    @Transactional
    public void revoke(Long userId, String sid) {
        UserSession s = repository.findById(sid)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session not found"));
        s.setRevokedAt(Instant.now());
        repository.save(s);
        cache.remove(sid);
    }

    /** Signs out every other device. Returns how many were signed out. */
    @Transactional
    public int revokeAllExcept(Long userId, String keepSid) {
        int n = 0;
        for (UserSession s : repository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastActiveAtDesc(userId, Instant.now())) {
            if (s.getId().equals(keepSid)) continue;
            s.setRevokedAt(Instant.now());
            repository.save(s);
            cache.remove(s.getId());
            n++;
        }
        return n;
    }

    private static String clientIp(HttpServletRequest request) {
        // Only trust X-Forwarded-For if you run behind a proxy you control; otherwise use the socket address.
        return request.getRemoteAddr();
    }
}
