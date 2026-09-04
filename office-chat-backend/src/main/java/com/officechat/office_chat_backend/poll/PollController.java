package com.officechat.office_chat_backend.poll;

import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.poll.dto.CreatePollRequest;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PollController {

    private final PollService pollService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping("/api/conversations/{id}/polls")
    public MessageResponse createPoll(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody CreatePollRequest request
    ) {
        MessageResponse response = pollService.createPoll(conversationId, currentUser, request);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, response);
        return response;
    }

    @PostMapping("/api/polls/{pollId}/vote/{optionId}")
    public MessageResponse vote(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("pollId") Long pollId,
            @PathVariable("optionId") Long optionId
    ) {
        MessageResponse response = pollService.vote(pollId, optionId, currentUser);
        messagingTemplate.convertAndSend("/topic/conversation." + response.conversationId(), response);
        return response;
    }
}
