package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.conversation.ConversationMember;
import com.officechat.office_chat_backend.conversation.ConversationMemberRepository;
import com.officechat.office_chat_backend.conversation.ConversationRepository;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.file.Attachment;
import com.officechat.office_chat_backend.file.AttachmentRepository;
import com.officechat.office_chat_backend.file.dto.AttachmentResponse;
import com.officechat.office_chat_backend.message.dto.MessageResponse;
import com.officechat.office_chat_backend.message.dto.ReactionDetailResponse;
import com.officechat.office_chat_backend.poll.PollRepository;
import com.officechat.office_chat_backend.poll.PollService;
import com.officechat.office_chat_backend.poll.dto.PollResponse;
import com.officechat.office_chat_backend.presence.PresenceService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final ConversationMemberRepository conversationMemberRepository;
    private final AttachmentRepository attachmentRepository;
    private final PresenceService presenceService;
    private final PollRepository pollRepository;
    private final PollService pollService;

    @Transactional
    public MessageResponse send(Long conversationId, User sender, String content, MessageType type, Long attachmentId) {
        conversationService.assertMember(conversationId, sender.getId());

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        if (conversation.isOnlyAdminsCanPost() || conversation.isAnnouncement()) {
            boolean isAdmin = "ADMIN".equals(sender.getRole());
            if (!isAdmin) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Only administrators can post in this conversation");
            }
        }

        Attachment attachment = null;
        if (attachmentId != null) {
            attachment = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));

            if (!attachment.getUploadedBy().getId().equals(sender.getId())
                    || !attachment.getConversationId().equals(conversationId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This attachment does not belong to you or this conversation");
            }
        }

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .content(content != null ? content : "")
                .type(resolveType(type, attachment))
                .build();

        message = messageRepository.save(message);

        List<AttachmentResponse> attachmentResponses = Collections.emptyList();
        if (attachment != null) {
            attachment.setMessage(message);
            attachmentRepository.save(attachment);
            attachmentResponses = List.of(AttachmentResponse.from(attachment));
        }

        List<ConversationMember> otherMembers = conversationMemberRepository.findByConversationId(conversationId).stream()
                .filter(m -> !m.getUser().getId().equals(sender.getId()))
                .toList();

        boolean isDelivered = otherMembers.stream().anyMatch(om -> presenceService.isOnline(om.getUser().getId()));

        return MessageResponse.from(message, attachmentResponses, Collections.emptyMap(), Collections.emptyList(), null, false, isDelivered);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getHistory(Long conversationId, Long requestingUserId, int page, int size) {
        conversationService.assertMember(conversationId, requestingUserId);

        Pageable pageable = PageRequest.of(page, size);
        List<Message> messages = new ArrayList<>(messageRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, pageable)
                .getContent());

        Collections.reverse(messages);

        List<Long> msgIds = messages.stream().map(Message::getId).toList();
        List<MessageReaction> allReactions = msgIds.isEmpty() ? Collections.emptyList() : reactionRepository.findByMessageIdIn(msgIds);
        
        Map<Long, Map<String, Integer>> reactionCountsByMsg = allReactions.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMessage().getId(),
                        Collectors.groupingBy(MessageReaction::getEmoji, Collectors.summingInt(r -> 1))
                ));

        Map<Long, List<ReactionDetailResponse>> reactionDetailsByMsg = allReactions.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMessage().getId(),
                        Collectors.mapping(
                                r -> new ReactionDetailResponse(r.getUser().getId(), r.getUser().getName(), r.getEmoji()),
                                Collectors.toList()
                        )
                ));

        List<ConversationMember> members = conversationMemberRepository.findByConversationId(conversationId);
        List<ConversationMember> otherMembers = members.stream()
                .filter(m -> !m.getUser().getId().equals(requestingUserId))
                .toList();

        return messages.stream()
                .map(m -> {
                    List<AttachmentResponse> atts = attachmentRepository.findByMessageId(m.getId()).stream()
                            .map(AttachmentResponse::from)
                            .toList();

                    boolean isRead = otherMembers.stream().anyMatch(om ->
                            om.getLastReadAt() != null && !om.getLastReadAt().isBefore(m.getCreatedAt())
                    );

                    boolean isDelivered = isRead || otherMembers.stream().anyMatch(om ->
                            presenceService.isOnline(om.getUser().getId())
                    );

                    Map<String, Integer> reactions = reactionCountsByMsg.getOrDefault(m.getId(), Collections.emptyMap());
                    List<ReactionDetailResponse> details = reactionDetailsByMsg.getOrDefault(m.getId(), Collections.emptyList());

                    PollResponse pollResp = null;
                    if (m.getType() == MessageType.POLL) {
                        pollResp = pollRepository.findByMessageId(m.getId())
                                .map(p -> pollService.getPollResponse(p, requestingUserId))
                                .orElse(null);
                    }

                    return MessageResponse.from(m, atts, reactions, details, pollResp, isRead, isDelivered);
                })
                .toList();
    }

    @Transactional
    public MessageResponse toggleReaction(Long messageId, User user, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        conversationService.assertMember(message.getConversation().getId(), user.getId());

        Optional<MessageReaction> existing = reactionRepository.findByMessageIdAndUserIdAndEmoji(messageId, user.getId(), emoji);
        if (existing.isPresent()) {
            reactionRepository.delete(existing.get());
        } else {
            MessageReaction reaction = MessageReaction.builder()
                    .message(message)
                    .user(user)
                    .emoji(emoji)
                    .build();
            reactionRepository.save(reaction);
        }

        return getSingleMessageResponse(message, user.getId());
    }

    @Transactional
    public MessageResponse editMessage(Long messageId, User user, String newContent) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        if (!message.getSender().getId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only edit your own messages");
        }

        if (message.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot edit a deleted message");
        }

        // 15-minute edit window limit
        if (Duration.between(message.getCreatedAt(), Instant.now()).toMinutes() > 15) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Messages can only be edited within 15 minutes");
        }

        message.setContent(newContent != null ? newContent : "");
        message.setEditedAt(Instant.now());
        messageRepository.save(message);

        return getSingleMessageResponse(message, user.getId());
    }

    @Transactional
    public MessageResponse deleteMessage(Long messageId, User user) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        boolean isSender = message.getSender().getId().equals(user.getId());
        boolean isAdmin = "ADMIN".equals(user.getRole());

        if (!isSender && !isAdmin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to delete this message");
        }

        message.setDeletedAt(Instant.now());
        messageRepository.save(message);

        return getSingleMessageResponse(message, user.getId());
    }

    @Transactional
    public MessageResponse togglePin(Long messageId, User user) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserId(message.getConversation().getId(), user.getId());
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not a member of this conversation");
        }

        message.setPinned(!message.isPinned());
        messageRepository.save(message);

        return getSingleMessageResponse(message, user.getId());
    }

    private MessageResponse getSingleMessageResponse(Message message, Long requestingUserId) {
        List<AttachmentResponse> atts = attachmentRepository.findByMessageId(message.getId()).stream()
                .map(AttachmentResponse::from)
                .toList();

        List<MessageReaction> reactionsList = reactionRepository.findByMessageId(message.getId());
        Map<String, Integer> reactionCounts = reactionsList.stream()
                .collect(Collectors.groupingBy(MessageReaction::getEmoji, Collectors.summingInt(r -> 1)));

        List<ReactionDetailResponse> reactionDetails = reactionsList.stream()
                .map(r -> new ReactionDetailResponse(r.getUser().getId(), r.getUser().getName(), r.getEmoji()))
                .toList();

        List<ConversationMember> otherMembers = conversationMemberRepository.findByConversationId(message.getConversation().getId()).stream()
                .filter(m -> !m.getUser().getId().equals(requestingUserId))
                .toList();

        boolean isRead = otherMembers.stream().anyMatch(om ->
                om.getLastReadAt() != null && !om.getLastReadAt().isBefore(message.getCreatedAt())
        );
        boolean isDelivered = isRead || otherMembers.stream().anyMatch(om ->
                presenceService.isOnline(om.getUser().getId())
        );

        PollResponse pollResp = null;
        if (message.getType() == MessageType.POLL) {
            pollResp = pollRepository.findByMessageId(message.getId())
                    .map(p -> pollService.getPollResponse(p, requestingUserId))
                    .orElse(null);
        }

        return MessageResponse.from(message, atts, reactionCounts, reactionDetails, pollResp, isRead, isDelivered);
    }

    private MessageType resolveType(MessageType requestedType, Attachment attachment) {
        if (requestedType != null) {
            return requestedType;
        }
        if (attachment == null) {
            return MessageType.TEXT;
        }
        String mime = attachment.getMimeType();
        if (mime == null) {
            return MessageType.FILE;
        }
        if (mime.startsWith("audio/")) return MessageType.AUDIO;
        if (mime.startsWith("video/")) return MessageType.VIDEO;
        return MessageType.FILE;
    }
}