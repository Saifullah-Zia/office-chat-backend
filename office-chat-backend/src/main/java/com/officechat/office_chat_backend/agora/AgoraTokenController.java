package com.officechat.office_chat_backend.agora;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.user.User;
import io.agora.media.RtcTokenBuilder2;
import io.agora.media.RtcTokenBuilder2.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agora tokens for calls. A token is only issued for the call channel of a chat the caller belongs to,
 * and always for the caller's own user id. Tokens are short-lived; the app renews them during long calls.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agora")
public class AgoraTokenController {

    /** Call channels are named after their conversation. */
    private static final Pattern CALL_CHANNEL = Pattern.compile("^call_conv_(\\d+)$");
    private static final int TOKEN_TTL_SECONDS = 2 * 3600;
    /** A person's screen share joins as a second participant: their user id plus this offset. */
    public static final int SCREEN_UID_OFFSET = 1_000_000_000;

    private final ConversationService conversationService;

    @Value("${agora.app.id:}")
    private String appId;

    @Value("${agora.app.certificate:}")
    private String appCertificate;

    @GetMapping("/token")
    public ResponseEntity<Map<String, Object>> getAgoraToken(
            @AuthenticationPrincipal User currentUser,
            @RequestParam String channelName,
            @RequestParam(defaultValue = "false") boolean screen
    ) {
        Matcher m = CALL_CHANNEL.matcher(channelName);
        if (!m.matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown call channel");
        if (!conversationService.isMember(Long.parseLong(m.group(1)), currentUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You're not in this conversation");
        }
        // Always the caller's own id: nobody can join a call as someone else.
        int uid = Math.toIntExact(currentUser.getId()) + (screen ? SCREEN_UID_OFFSET : 0);

        String token = new RtcTokenBuilder2().buildTokenWithUid(
                appId, appCertificate, channelName, uid, Role.ROLE_PUBLISHER, TOKEN_TTL_SECONDS, TOKEN_TTL_SECONDS);

        return ResponseEntity.ok(Map.of(
                "appId", appId,
                "token", token,
                "channelName", channelName,
                "uid", uid,
                "expiresInSeconds", TOKEN_TTL_SECONDS
        ));
    }
}
