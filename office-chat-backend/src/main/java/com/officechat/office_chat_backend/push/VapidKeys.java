package com.officechat.office_chat_backend.push;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Base64;
import java.util.Properties;

/**
 * The server's VAPID key pair (identifies us to browser push services).
 * Taken from PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY when set; otherwise generated once and
 * kept in a local file, because changing keys invalidates every existing browser subscription.
 */
@Slf4j
@Getter
@Component
public class VapidKeys {

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    /** Uncompressed public point, base64url: what browsers need as applicationServerKey. */
    private final String publicKeyBase64;
    /** P-256 curve parameters */
    static final ECParameterSpec P256 = curveParams();

    public VapidKeys(
            @Value("${app.push.vapid-public-key:}") String configuredPublic,
            @Value("${app.push.vapid-private-key:}") String configuredPrivate,
            @Value("${app.push.vapid-key-file:vapid-keys.properties}") String keyFile
    ) throws GeneralSecurityException, IOException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));

        String pub = configuredPublic;
        String priv = configuredPrivate;
        if (pub.isBlank() || priv.isBlank()) {
            Path path = Path.of(keyFile);
            Properties props = new Properties();
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) { props.load(in); }
                pub = props.getProperty("public", "");
                priv = props.getProperty("private", "");
            }
            if (pub.isBlank() || priv.isBlank()) {
                KeyPair kp = gen.generateKeyPair();
                pub = b64(encodePoint((ECPublicKey) kp.getPublic()));
                priv = b64(toFixed(((ECPrivateKey) kp.getPrivate()).getS()));
                props.setProperty("public", pub);
                props.setProperty("private", priv);
                try (OutputStream out = Files.newOutputStream(path)) {
                    props.store(out, "Web Push VAPID keys - keep private, don't change (it breaks existing subscriptions)");
                }
                log.info("Generated new VAPID keys in {}", path.toAbsolutePath());
            }
        }
        this.publicKey = decodePublic(Base64.getUrlDecoder().decode(pub));
        this.privateKey = (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(priv)), P256));
        this.publicKeyBase64 = b64(encodePoint(publicKey));
    }

    private static ECParameterSpec curveParams() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }

    /** 65-byte uncompressed point (0x04 || X || Y). */
    public static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(toFixed(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(toFixed(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    public static ECPublicKey decodePublic(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 0x04) throw new InvalidKeyException("Expected an uncompressed P-256 point");
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(point, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    private static byte[] toFixed(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
