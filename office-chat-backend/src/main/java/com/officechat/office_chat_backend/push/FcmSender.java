package com.officechat.office_chat_backend.push;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends Firebase Cloud Messaging (FCM) messages to the Android app: the push that can wake a phone
 * and make it ring even when the app is closed. Talks to Google's HTTP v1 API directly (no Firebase
 * library), authenticated with the Firebase service-account key.
 * Off unless FIREBASE_SERVICE_ACCOUNT is set (the key file's JSON, or that JSON base64-encoded).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmSender {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${app.fcm.service-account:}")
    private String serviceAccount;

    private String projectId;
    private String clientEmail;
    private PrivateKey privateKey;
    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void init() {
        if (serviceAccount == null || serviceAccount.isBlank()) {
            log.info("Android push (FCM) is off: FIREBASE_SERVICE_ACCOUNT is not set");
            return;
        }
        try {
            String json = serviceAccount.trim().startsWith("{")
                    ? serviceAccount.trim()
                    : new String(Base64.getMimeDecoder().decode(serviceAccount.trim()), StandardCharsets.UTF_8);
            Map<String, Object> key = objectMapper.readValue(json, Map.class);
            projectId = (String) key.get("project_id");
            clientEmail = (String) key.get("client_email");
            String pem = ((String) key.get("private_key"))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            log.info("Android push (FCM) is on for Firebase project {}", projectId);
        } catch (Exception e) {
            projectId = null;
            log.error("FIREBASE_SERVICE_ACCOUNT couldn't be read, Android push is off: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return projectId != null && privateKey != null;
    }

    /**
     * Sends a data message (the app decides how to show it). Completes with the HTTP status:
     * 404 means the token is gone (app uninstalled / signed out) and should be deleted.
     */
    public CompletableFuture<Integer> send(String deviceToken, Map<String, Object> data, int ttlSeconds) {
        return send(deviceToken, data, ttlSeconds, true);
    }

    /**
     * highPriority=false for messages that show nothing on the phone (e.g. "stop ringing, they answered").
     * Google demotes apps whose high-priority messages often show nothing, and then calls arrive late.
     */
    public CompletableFuture<Integer> send(String deviceToken, Map<String, Object> data, int ttlSeconds, boolean highPriority) {
        if (!isEnabled()) return CompletableFuture.completedFuture(0);
        try {
            Map<String, String> strings = new LinkedHashMap<>();
            data.forEach((k, v) -> { if (v != null) strings.put(k, String.valueOf(v)); }); // FCM data is text only
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("data", strings);
            // "high" wakes a sleeping phone straight away (needed for ringing).
            message.put("android", Map.of("priority", highPriority ? "high" : "normal", "ttl", ttlSeconds + "s"));
            String body = objectMapper.writeValueAsString(Map.of("message", message));

            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
                if (res.statusCode() == 401) forgetAccessToken(); // rejected: get a fresh one next time
                if (res.statusCode() >= 400 && res.statusCode() != 404) {
                    log.debug("FCM returned {}: {}", res.statusCode(), res.body());
                }
                return res.statusCode();
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private synchronized void forgetAccessToken() {
        accessToken = null;
    }

    /** Google OAuth access token from the service-account key, reused until shortly before it expires. */
    @SuppressWarnings("unchecked")
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(60))) return accessToken;
        long now = Instant.now().getEpochSecond();
        String header = B64URL.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64URL.encodeToString(objectMapper.writeValueAsString(Map.of(
                "iss", clientEmail, "scope", SCOPE, "aud", TOKEN_URL, "iat", now, "exp", now + 3600
        )).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String assertion = header + "." + claims + "." + B64URL.encodeToString(signer.sign());

        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Google token request failed: " + res.statusCode());
        Map<String, Object> token = objectMapper.readValue(res.body(), Map.class);
        accessToken = (String) token.get("access_token");
        accessTokenExpires = Instant.now().plusSeconds(((Number) token.getOrDefault("expires_in", 3600)).longValue());
        return accessToken;
    }
}
