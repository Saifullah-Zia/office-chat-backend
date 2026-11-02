package com.officechat.office_chat_backend.admin;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Usage numbers for the admin dashboard. Counts only; message text is never read here.
 * Direct chats are reported as a total, never by name, so admins can't see who talks to whom.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final EntityManager em;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(int days) {
        int window = Math.max(1, Math.min(days, 365));
        Instant now = Instant.now();
        Instant since = now.minus(window, ChronoUnit.DAYS);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("users", count("select count(*) from users"));
        totals.put("activeAccounts", count("select count(*) from users where active = true"));
        totals.put("groups", count("select count(*) from conversations where type = 'GROUP'"));
        totals.put("directChats", count("select count(*) from conversations where type = 'DIRECT'"));
        totals.put("messages", count("select count(*) from messages"));
        totals.put("files", count("select count(*) from attachments"));
        totals.put("storageBytes", count("select coalesce(sum(file_size), 0) from attachments"));

        // "Active" = sent a message or was online in the period.
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("today", activeSince(now.minus(1, ChronoUnit.DAYS)));
        active.put("week", activeSince(now.minus(7, ChronoUnit.DAYS)));
        active.put("month", activeSince(now.minus(30, ChronoUnit.DAYS)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", window);
        result.put("totals", totals);
        result.put("activeUsers", active);
        result.put("messagesPerDay", messagesPerDay(since, window));
        result.put("messagesByType", messagesByType(since));
        result.put("topSenders", topSenders(since));
        result.put("topGroups", topGroups(since));
        result.put("messagesInPeriod", count("select count(*) from messages where created_at >= ?1", since));
        return result;
    }

    private long count(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, toSql(params[i]));
        return ((Number) q.getSingleResult()).longValue();
    }

    private long activeSince(Instant since) {
        return count("""
                select count(*) from users u where u.active = true and (
                    u.last_seen_at >= ?1
                    or exists (select 1 from messages m where m.sender_id = u.id and m.created_at >= ?1))
                """, since);
    }

    /** One entry per day (UTC), including days with no messages. */
    private List<Map<String, Object>> messagesPerDay(Instant since, int window) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select cast(date_trunc('day', created_at at time zone 'UTC') as date) as d, count(*)
                from messages where created_at >= ?1
                group by d order by d
                """).setParameter(1, toSql(since)).getResultList();
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (Object[] r : rows) {
            LocalDate day = r[0] instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(r[0].toString());
            byDay.put(day, ((Number) r[1]).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LocalDate d = today.minusDays(window - 1L); !d.isAfter(today); d = d.plusDays(1)) {
            out.add(Map.of("date", d.toString(), "count", byDay.getOrDefault(d, 0L)));
        }
        return out;
    }

    private List<Map<String, Object>> messagesByType(Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select type, count(*) from messages where created_at >= ?1
                group by type order by count(*) desc
                """).setParameter(1, toSql(since)).getResultList();
        return rows.stream()
                .map(r -> Map.<String, Object>of("type", String.valueOf(r[0]), "count", ((Number) r[1]).longValue()))
                .toList();
    }

    private List<Map<String, Object>> topSenders(Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select u.id, u.name, coalesce(u.department, ''), count(*) as c
                from messages m join users u on u.id = m.sender_id
                where m.created_at >= ?1
                group by u.id, u.name, u.department order by c desc limit 5
                """).setParameter(1, toSql(since)).getResultList();
        return rows.stream().map(r -> Map.<String, Object>of(
                "userId", ((Number) r[0]).longValue(),
                "name", String.valueOf(r[1]),
                "department", String.valueOf(r[2]),
                "count", ((Number) r[3]).longValue())).toList();
    }

    private List<Map<String, Object>> topGroups(Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select c.id, coalesce(c.name, 'Group'), count(*) as n
                from messages m join conversations c on c.id = m.conversation_id
                where m.created_at >= ?1 and c.type = 'GROUP'
                group by c.id, c.name order by n desc limit 5
                """).setParameter(1, toSql(since)).getResultList();
        return rows.stream().map(r -> Map.<String, Object>of(
                "conversationId", ((Number) r[0]).longValue(),
                "name", String.valueOf(r[1]),
                "count", ((Number) r[2]).longValue())).toList();
    }

    private static Object toSql(Object value) {
        return value instanceof Instant i ? Timestamp.from(i) : value;
    }
}
