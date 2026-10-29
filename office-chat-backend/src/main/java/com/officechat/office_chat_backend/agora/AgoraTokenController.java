package com.officechat.office_chat_backend.agora;

import io.agora.media.RtcTokenBuilder2;
import io.agora.media.RtcTokenBuilder2.Role;
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
        int tokenExpirationInSeconds = 3600 * 24; // 24 hours validity
        int privilegeExpirationInSeconds = 3600 * 24;

        RtcTokenBuilder2 tokenBuilder = new RtcTokenBuilder2();

        String token = tokenBuilder.buildTokenWithUid(
                appId,
                appCertificate,
                channelName,
                uid,
                Role.ROLE_PUBLISHER,
                tokenExpirationInSeconds,
                privilegeExpirationInSeconds
        );

        return ResponseEntity.ok(Map.of(
                "appId", appId,
                "token", token,
                "channelName", channelName,
                "uid", uid
        ));
    }
}
