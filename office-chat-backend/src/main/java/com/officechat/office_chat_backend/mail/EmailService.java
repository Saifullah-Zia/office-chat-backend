package com.officechat.office_chat_backend.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Sends email through Resend (https://resend.com). Off unless RESEND_API_KEY is set. */
@Slf4j
@Service
public class EmailService {

    private final String apiKey;
    private final String from;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public EmailService(@Value("${app.mail.resend-api-key:}") String apiKey,
                        @Value("${app.mail.from:}") String from,
                        ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.from = from;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return !apiKey.isBlank() && !from.isBlank();
    }

    /** Best-effort; returns whether the provider accepted the email. Never throws. */
    public boolean send(String to, String subject, String html, String text) {
        if (!isEnabled()) {
            log.warn("Email not sent to {} ({}): RESEND_API_KEY / MAIL_FROM not configured", to, subject);
            return false;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "from", from, "to", new String[]{to}, "subject", subject, "html", html, "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Resend rejected email to {}: {} {}", to, res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Email to {} failed: {}", to, e.getMessage());
            return false;
        }
    }
}
