package com.officechat.office_chat_backend.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Company-wide settings, cached in memory (read on every message send). */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final AppSettingRepository repository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final String NONE = "\u0000";

    public String get(String key, String fallback) {
        String v = cache.computeIfAbsent(key, k -> repository.findById(k).map(AppSetting::getValue).orElse(NONE));
        return NONE.equals(v) ? fallback : v;
    }

    public void set(String key, String value) {
        repository.save(new AppSetting(key, value));
        cache.put(key, value);
    }
}
