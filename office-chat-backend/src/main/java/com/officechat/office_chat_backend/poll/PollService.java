package com.officechat.office_chat_backend.poll;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationRepository;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.message.MessageType;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.poll.dto.CreatePollRequest;
import com.officechat.office_chat_backend.poll.dto.PollOptionResponse;
import com.officechat.office_chat_backend.poll.dto.PollResponse;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final PollOptionRepository optionRepository;
    private final PollVoteRepository voteRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;

    @Transactional
    public MessageResponse createPoll(Long conversationId, User sender, CreatePollRequest request) {
        conversationService.assertMember(conversationId, sender.getId());

        if (request.question() == null || request.question().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Poll question cannot be empty");
        }

        if (request.options() == null || request.options().size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Poll must have at least 2 options");
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .content("📊 Poll: " + request.question())
                .type(MessageType.POLL)
                .build();
        message = messageRepository.save(message);

        Poll poll = Poll.builder()
                .message(message)
                .question(request.question().trim())
                .multipleAnswers(request.multipleAnswers())
                .build();
        poll = pollRepository.save(poll);

        for (String optText : request.options()) {
            if (optText != null && !optText.trim().isEmpty()) {
                optionRepository.save(PollOption.builder()
                        .poll(poll)
                        .optionText(optText.trim())
                        .build());
            }
        }

        PollResponse pollResp = getPollResponse(poll, sender.getId());
        return MessageResponse.from(message, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), pollResp, false, true);
    }

    @Transactional
    public MessageResponse vote(Long pollId, Long optionId, User user) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Poll not found"));

        conversationService.assertMember(poll.getMessage().getConversation().getId(), user.getId());

        PollOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Poll option not found"));

        Optional<PollVote> existing = voteRepository.findByPollIdAndOptionIdAndUserId(pollId, optionId, user.getId());

        if (existing.isPresent()) {
            // Unvote if already voted on this option
            voteRepository.delete(existing.get());
        } else {
            if (!poll.isMultipleAnswers()) {
                // Remove all previous votes by user on single-choice poll
                voteRepository.deleteByPollIdAndUserId(pollId, user.getId());
            }
            voteRepository.save(PollVote.builder()
                    .poll(poll)
                    .option(option)
                    .user(user)
                    .build());
        }

        PollResponse pollResp = getPollResponse(poll, user.getId());
        Message message = poll.getMessage();
        return MessageResponse.from(message, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), pollResp, false, true);
    }

    @Transactional(readOnly = true)
    public PollResponse getPollResponse(Poll poll, Long requestingUserId) {
        List<PollOption> options = optionRepository.findByPollId(poll.getId());
        List<PollVote> allVotes = voteRepository.findByPollId(poll.getId());

        Map<Long, List<PollVote>> votesByOption = allVotes.stream()
                .collect(Collectors.groupingBy(v -> v.getOption().getId()));

        List<PollOptionResponse> optionResponses = options.stream().map(opt -> {
            List<PollVote> optVotes = votesByOption.getOrDefault(opt.getId(), Collections.emptyList());
            int count = optVotes.size();
            boolean votedByMe = optVotes.stream().anyMatch(v -> v.getUser().getId().equals(requestingUserId));
            List<String> voterNames = optVotes.stream().map(v -> v.getUser().getName()).toList();
            return new PollOptionResponse(opt.getId(), opt.getOptionText(), count, votedByMe, voterNames);
        }).toList();

        int totalVotes = allVotes.size();

        return new PollResponse(
                poll.getId(),
                poll.getMessage().getId(),
                poll.getQuestion(),
                poll.isMultipleAnswers(),
                totalVotes,
                optionResponses
        );
    }
}
