package com.officechat.office_chat_backend.push;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPushSenderTest {

    private static final Base64.Decoder D = Base64.getUrlDecoder();

    /** RFC 8291 section 5: the worked example must encrypt to exactly the published bytes. */
    @Test
    void encryptsTheRfc8291Example() throws Exception {
        byte[] uaPublic = D.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = D.decode("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] asPublic = D.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] asPrivate = D.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] salt = D.decode("DGv6ra1nlYgDCS1FRnbzlw");

        KeyPair as = new KeyPair(
                VapidKeys.decodePublic(asPublic),
                KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, asPrivate), VapidKeys.P256)));

        byte[] out = WebPushSender.encrypt(
                "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8), uaPublic, auth, as, salt);

        assertEquals(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                Base64.getUrlEncoder().withoutPadding().encodeToString(out));
    }

    @Test
    void onlyKnownPushServicesAreAllowed() {
        assertTrue(WebPushSender.isAllowedEndpoint("https://fcm.googleapis.com/fcm/send/abc"));
        assertTrue(WebPushSender.isAllowedEndpoint("https://updates.push.services.mozilla.com/wpush/v2/abc"));
        assertTrue(WebPushSender.isAllowedEndpoint("https://web.push.apple.com/abc"));
        assertFalse(WebPushSender.isAllowedEndpoint("http://fcm.googleapis.com/fcm/send/abc"));
        assertFalse(WebPushSender.isAllowedEndpoint("https://169.254.169.254/latest/meta-data"));
        assertFalse(WebPushSender.isAllowedEndpoint("https://evilfcm.googleapis.com.attacker.net/x"));
        assertFalse(WebPushSender.isAllowedEndpoint("https://localhost:8080/api/admin"));
    }
}
