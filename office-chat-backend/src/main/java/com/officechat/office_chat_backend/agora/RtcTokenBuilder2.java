package com.officechat.office_chat_backend.agora;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.TreeMap;
import java.util.zip.Deflater;

public class RtcTokenBuilder2 {

    public enum Role {
        ROLE_PUBLISHER(1),
        ROLE_SUBSCRIBER(2);

        public final int value;

        Role(int value) {
            this.value = value;
        }
    }

    public static String buildTokenWithUid(
            String appId,
            String appCertificate,
            String channelName,
            int uid,
            Role role,
            int tokenExpirationInSeconds,
            int privilegeExpirationInSeconds
    ) {
        if (appId == null || appId.isEmpty()) {
            return "";
        }
        if (appCertificate == null || appCertificate.isEmpty()) {
            return "";
        }

        long now = System.currentTimeMillis() / 1000L;
        long tokenExpire = now + tokenExpirationInSeconds;
        long privilegeExpire = now + privilegeExpirationInSeconds;

        String uidStr = uid == 0 ? "" : String.valueOf(uid);

        return buildTokenWithAccount(appId, appCertificate, channelName, uidStr, role, (int) tokenExpire, (int) privilegeExpire);
    }

    public static String buildTokenWithAccount(
            String appId,
            String appCertificate,
            String channelName,
            String account,
            Role role,
            int tokenExpire,
            int privilegeExpire
    ) {
        try {
            // Service RTC Service ID = 1
            short serviceType = 1;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // Version 007 prefix
            dos.write("007".getBytes(StandardCharsets.UTF_8));
            byte[] appIdBytes = hexStringToByteArray(appId);
            if (appIdBytes.length == 16) {
                dos.write(appIdBytes);
            } else {
                dos.write(appId.getBytes(StandardCharsets.UTF_8));
            }

            // Issue Timestamp
            long issueTs = System.currentTimeMillis() / 1000L;
            dos.writeInt(Integer.reverseBytes((int) issueTs));

            // Salt
            int salt = (int) (Math.random() * 99999999);
            dos.writeInt(Integer.reverseBytes(salt));

            // Service Map
            TreeMap<Short, Integer> privileges = new TreeMap<>();
            // RTC Privileges: 1 = join channel, 2 = publish audio, 3 = publish video, 4 = publish data
            privileges.put((short) 1, privilegeExpire);
            if (role == Role.ROLE_PUBLISHER) {
                privileges.put((short) 2, privilegeExpire);
                privileges.put((short) 3, privilegeExpire);
                privileges.put((short) 4, privilegeExpire);
            }

            ByteArrayOutputStream serviceBaos = new ByteArrayOutputStream();
            DataOutputStream serviceDos = new DataOutputStream(serviceBaos);

            // Channel Name
            byte[] channelNameBytes = channelName.getBytes(StandardCharsets.UTF_8);
            serviceDos.writeShort(Short.reverseBytes((short) channelNameBytes.length));
            serviceDos.write(channelNameBytes);

            // Account / Uid
            byte[] accountBytes = account.getBytes(StandardCharsets.UTF_8);
            serviceDos.writeShort(Short.reverseBytes((short) accountBytes.length));
            serviceDos.write(accountBytes);

            // Privileges Count
            serviceDos.writeShort(Short.reverseBytes((short) privileges.size()));
            for (var entry : privileges.entrySet()) {
                serviceDos.writeShort(Short.reverseBytes(entry.getKey()));
                serviceDos.writeInt(Integer.reverseBytes(entry.getValue()));
            }

            byte[] servicePayload = serviceBaos.toByteArray();
            dos.writeShort(Short.reverseBytes(serviceType));
            dos.writeShort(Short.reverseBytes((short) servicePayload.length));
            dos.write(servicePayload);

            byte[] uncompressed = baos.toByteArray();

            // Calculate HMAC-SHA256 signature
            byte[] signingKey = hmacSha256(appCertificate.getBytes(StandardCharsets.UTF_8), intToBytes((int) issueTs));
            byte[] signature = hmacSha256(signingKey, uncompressed);

            // Final token buffer: signature + uncompressed payload
            ByteArrayOutputStream finalBaos = new ByteArrayOutputStream();
            finalBaos.write(signature);
            finalBaos.write(uncompressed);

            // Compress payload using ZLIB/Deflater
            byte[] compressed = compress(finalBaos.toByteArray());

            return "007" + Base64.getEncoder().encodeToString(compressed);

        } catch (Exception e) {
            System.err.println("Agora token generation error: " + e.getMessage());
            return "";
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key, "HmacSHA256");
        mac.init(secretKey);
        return mac.doFinal(data);
    }

    private static byte[] intToBytes(int val) {
        return new byte[]{
                (byte) (val & 0xFF),
                (byte) ((val >> 8) & 0xFF),
                (byte) ((val >> 16) & 0xFF),
                (byte) ((val >> 24) & 0xFF)
        };
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) return s.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            outputStream.write(buffer, 0, count);
        }
        try {
            outputStream.close();
        } catch (Exception ignored) {
        }
        return outputStream.toByteArray();
    }
}
