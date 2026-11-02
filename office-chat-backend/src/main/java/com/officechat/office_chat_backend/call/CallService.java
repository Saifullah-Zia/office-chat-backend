package com.officechat.office_chat_backend.call;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationRepository;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CallService {

    private final CallRepository callRepository;
    private final CallEventService callEventService;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;

    @Transactional
    public Call initiate(Long conversationId, User initiator, CallType type) {
        conversationService.assertMember(conversationId, initiator.getId());

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        Call call = Call.builder()
                .conversation(conversation)
                .initiator(initiator)
                .type(type)
                .status(CallStatus.RINGING)
                .build();

        return callRepository.save(call);
    }

    @Transactional
    public Call accept(Long callId, User user) {
        Call call = findCallAndAssertMember(callId, user.getId());
        call.setStatus(CallStatus.ONGOING);
        call.setAnsweredAt(Instant.now());
        return callRepository.save(call);
    }

    @Transactional
    public Call reject(Long callId, User user) {
        Call call = findCallAndAssertMember(callId, user.getId());
        if (call.getEndedAt() != null) return call; // already closed by the other side
        call.setStatus(CallStatus.REJECTED);
        call.setEndedAt(Instant.now());
        Call saved = callRepository.save(call);
        callEventService.postEnded(saved);
        return saved;
    }

    @Transactional
    public Call end(Long callId, User user) {
        Call call = findCallAndAssertMember(callId, user.getId());
        // Both people may hang up; only the first one closes the call (and posts the chat entry).
        if (call.getEndedAt() != null) return call;

        // if it was still ringing and never answered, log it as missed rather than "ended"
        call.setStatus(call.getAnsweredAt() == null ? CallStatus.MISSED : CallStatus.ENDED);
        call.setEndedAt(Instant.now());
        Call saved = callRepository.save(call);
        callEventService.postEnded(saved);
        return saved;
    }

    public List<Call> getHistory(Long conversationId, Long requestingUserId) {
        conversationService.assertMember(conversationId, requestingUserId);
        return callRepository.findByConversationIdOrderByStartedAtDesc(conversationId);
    }

    /** Most recent calls across every conversation the user belongs to. */
    @Transactional(readOnly = true)
    public List<Call> getMyHistory(Long userId, int limit) {
        List<Long> convIds = conversationService.getConversationIdsForUser(userId);
        if (convIds.isEmpty()) return List.of();
        return callRepository.findByConversationIdInOrderByStartedAtDesc(
                convIds, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    private Call findCallAndAssertMember(Long callId, Long userId) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Call not found"));
        conversationService.assertMember(call.getConversation().getId(), userId);
        return call;
    }
}