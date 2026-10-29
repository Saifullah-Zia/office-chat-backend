package com.officechat.office_chat_backend.file;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs attachment download URLs. Browsers load images/audio with plain {@code <img src>} requests
 * that can't carry the JWT, so instead of leaving downloads open to anyone who guesses an id,
 * every URL we hand out carries an expiring HMAC signature tied to that attachment.
 */
@Component
public class DownloadUrlSigner {

    private static final Duration TTL = Duration.ofDays(7);
    private static volatile DownloadUrlSigner instance;

    @Value("${jwt.secret}")
    private String secret;

    private SecretKeySpec key;

    @PostConstruct
    void init() {
        // Derive a separate key so download signatures can't be confused with JWTs.
        key = new SecretKeySpec(("download:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        instance = this;
    }

    /** Used by the static {@code AttachmentResponse.from} factory. */
    public static String signedPath(Long attachmentId) {
        String base = "/api/files/" + attachmentId + "/download";
        DownloadUrlSigner signer = instance;
        if (signer == null) return base; // not yet initialised (e.g. unit tests)
        long exp = Instant.now().plus(TTL).getEpochSecond();
        return base + "?exp=" + exp + "&sig=" + signer.sign(attachmentId, exp);
    }

    public boolean isValid(Long attachmentId, Long exp, String sig) {
        if (exp == null || sig == null || sig.isBlank()) return false;
        if (Instant.now().getEpochSecond() > exp) return false;
        byte[] expected = sign(attachmentId, exp).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(Long attachmentId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] raw = mac.doFinal((attachmentId + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign download URL", e);
        }
    }
}
