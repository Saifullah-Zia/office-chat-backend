package com.officechat.office_chat_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On Railway (or with APP_ENV=production), refuse to run with the development defaults that are in
 * application.properties, and warn about settings that are very likely wrong.
 */
@Slf4j
@Component
public class ProductionSafetyCheck {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean production = System.getenv("RAILWAY_ENVIRONMENT") != null || "production".equalsIgnoreCase(System.getenv("APP_ENV"));
        if (!production) return;

        String jwt = System.getenv("JWT_SECRET");
        if (jwt == null || jwt.length() < 32) {
            // The development secret is in git history; anyone could forge logins with it.
            throw new IllegalStateException("Set JWT_SECRET (64+ random characters) before running in production.");
        }
        if (System.getenv("AGORA_APP_CERTIFICATE") == null) {
            log.warn("AGORA_APP_CERTIFICATE is not set: calls use the development certificate from git history. Rotate it in the Agora console.");
        }
        if (allowedOrigins.contains("localhost")) {
            log.warn("CORS_ALLOWED_ORIGINS is still localhost; set it to the frontend's https address or the browser will block the app.");
        }
        if (!uploadDir.startsWith("/")) {
            log.warn("UPLOAD_DIR is '{}' (inside the container): uploaded files will be lost on redeploy. Point it at the volume, e.g. /data/uploads.", uploadDir);
        }
    }
}
