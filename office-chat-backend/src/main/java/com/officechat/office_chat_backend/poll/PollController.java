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
    private final com.officechat.office_chat_backend.message.MessageBroadcaster broadcaster;
    private final com.officechat.office_chat_backend.message.MessageService messageService;

    @PostMapping("/api/conversations/{id}/polls")
    public MessageResponse createPoll(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("id") Long conversationId,
            @RequestBody CreatePollRequest request
    ) {
        messageService.assertCanPost(conversationId, currentUser);
        MessageResponse response = pollService.createPoll(conversationId, currentUser, request);
        broadcaster.broadcastNew(response);
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

    @PostMapping("/api/polls/{pollId}/close")
    public MessageResponse close(
            @AuthenticationPrincipal User currentUser,
            @PathVariable("pollId") Long pollId
    ) {
        MessageResponse response = pollService.close(pollId, currentUser);
        messagingTemplate.convertAndSend("/topic/conversation." + response.conversationId(), response);
        return response;
    }
}
