package com.officechat.office_chat_backend.agora;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agora")
public class AgoraTokenController {

    @Value("${agora.app.id:}")
    private String appId;

    @Value("${agora.app.certificate:}")
    private String appCertificate;

    @GetMapping("/token")
    public ResponseEntity<Map<String, Object>> getAgoraToken(
            @RequestParam String channelName,
            @RequestParam(defaultValue = "0") int uid
    ) {
        int expirationInSeconds = 3600 * 24; // 24 hours validity

        String token = RtcTokenBuilder2.buildTokenWithUid(
                appId,
                appCertificate,
                channelName,
                uid,
                RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                expirationInSeconds,
                expirationInSeconds
        );

        return ResponseEntity.ok(Map.of(
                "appId", appId,
                "token", token,
                "channelName", channelName,
                "uid", uid
        ));
    }
}
