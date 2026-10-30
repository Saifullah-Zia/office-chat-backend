package com.officechat.office_chat_backend.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * RFC 6238 time-based one-time passwords (what Google Authenticator, Microsoft Authenticator,
 * 1Password, etc. generate): HMAC-SHA1, 30-second steps, 6 digits.
 */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private Totp() {}

    /** 160-bit random secret, Base32 encoded (the format authenticator apps expect). */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static String otpauthUrl(String issuer, String account, String secret) {
        String enc = java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String acc = java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + enc + ":" + acc + "?secret=" + secret + "&issuer=" + enc
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * Checks a code against the current step ±1 (allowing ~30s of clock drift).
     * @param lastUsedStep the step of the last accepted code (replay protection), or null
     * @return the matched step, or -1 if the code is wrong or was already used
     */
    public static long verify(String secret, String code, Long lastUsedStep) {
        if (secret == null || code == null) return -1;
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return -1;
        long now = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        byte[] key = base32Decode(secret);
        for (long step = now - 1; step <= now + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) continue;
            if (constantTimeEquals(generate(key, step), digits)) return step;
        }
        return -1;
    }

    static String generate(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) throw new IllegalArgumentException("Invalid Base32");
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
