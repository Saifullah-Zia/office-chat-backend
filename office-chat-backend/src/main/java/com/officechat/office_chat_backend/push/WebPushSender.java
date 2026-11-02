package com.officechat.office_chat_backend.push;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal Web Push client: RFC 8291 payload encryption (aes128gcm) + RFC 8292 VAPID auth,
 * using only JDK crypto.
 */
@Component
@RequiredArgsConstructor
public class WebPushSender {

    /** Push services browsers actually use; we never POST anywhere else. */
    private static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(
            "fcm.googleapis.com",            // Chrome, Edge (Chromium), Opera, Brave
            "push.services.mozilla.com",     // Firefox
            "notify.windows.com",            // legacy Edge
            "push.apple.com"                 // Safari
    );

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();

    private final VapidKeys vapid;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();

    @Value("${app.push.subject:mailto:admin@officechat.local}")
    private String subject;

    public static boolean isAllowedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase();
            return ALLOWED_HOST_SUFFIXES.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Sends one push; completes with the HTTP status (404/410 = subscription is gone). */
    public CompletableFuture<Integer> send(PushSubscription sub, byte[] payload) {
        try {
            byte[] body = encrypt(payload, B64.decode(sub.getP256dh()), B64.decode(sub.getAuth()));
            URI endpoint = URI.create(sub.getEndpoint());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("TTL", "86400")
                    .header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + vapidJwt(endpoint) + ", k=" + vapid.getPublicKeyBase64())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ── RFC 8291 ────────────────────────────────────────────────────────

    private byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, gen.generateKeyPair(), salt);
    }

    /** Deterministic core (ephemeral key and salt passed in), so it can be checked against RFC 8291's example. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt)
            throws GeneralSecurityException {
        byte[] asPublic = VapidKeys.encodePoint((ECPublicKey) as.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(VapidKeys.decodePublic(uaPublic), true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1});
        byte[] ikm = hmac(prkKey, keyInfo);

        byte[] prk = hmac(salt, ikm);
        byte[] cek = java.util.Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = java.util.Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2})); // 0x02 = last record

        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length)
                .put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic).put(ciphertext)
                .array();
    }

    // ── RFC 8292 ────────────────────────────────────────────────────────

    private String vapidJwt(URI endpoint) throws GeneralSecurityException {
        String aud = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        String header = B64E.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64E.encodeToString(("{\"aud\":\"" + aud + "\",\"exp\":"
                + Instant.now().plus(Duration.ofHours(12)).getEpochSecond()
                + ",\"sub\":\"" + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format"); // raw r||s, as JWS wants
        signer.initSign(vapid.getPrivateKey());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + B64E.encodeToString(signer.sign());
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        ByteBuffer buf = ByteBuffer.allocate(len);
        for (byte[] p : parts) buf.put(p);
        return buf.array();
    }
}
