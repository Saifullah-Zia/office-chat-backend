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
    private final StarredMessageRepository starredMessageRepository;
    private final com.officechat.office_chat_backend.common.RateLimiter rateLimiter;
    private final com.officechat.office_chat_backend.audit.AuditService auditService;
    private final MessageEditRepository editRepository;
    private final com.officechat.office_chat_backend.dlp.DlpService dlpService;

    private static final int MAX_MESSAGE_LENGTH = 10_000;

    /** Membership, admin-only and block checks shared by send and forward. */
    // @here / @channel / @all notify the whole group, so only group admins may use them.
    private static final java.util.regex.Pattern GROUP_MENTION =
            java.util.regex.Pattern.compile("(^|[^\\w@])@(here|channel|all)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private void assertCanUseGroupMention(Conversation conversation, User sender, String content) {
        if (content == null
                || conversation.getType() != com.officechat.office_chat_backend.conversation.ConversationType.GROUP
                || !GROUP_MENTION.matcher(content).find()) return;
        if (!conversationService.isConversationAdmin(conversation.getId(), sender.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only group admins can use @here, @channel or @all");
        }
    }

    /** Members only; respects "only admins can post" and blocks in direct chats. */
    public Conversation assertCanPost(Long conversationId, User sender) {
        conversationService.assertMember(conversationId, sender.getId());

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        if ((conversation.isOnlyAdminsCanPost() || conversation.isAnnouncement())
                && !conversationService.isConversationAdmin(conversationId, sender.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Only group admins can send messages"
            );
        }

        if (conversation.getType() == com.officechat.office_chat_backend.conversation.ConversationType.DIRECT) {
            boolean blocked = conversationMemberRepository.findByConversationId(conversationId).stream()
                    .map(m -> m.getUser().getId())
                    .filter(id -> !id.equals(sender.getId()))
                    .anyMatch(otherId -> conversationService.isBlockedBetween(sender.getId(), otherId));
            if (blocked) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You can't send messages in this chat");
            }
        }

        return conversation;
    }

    @Transactional
    public MessageResponse send(Long conversationId, User sender, String content, MessageType type, Long attachmentId) {
        return send(conversationId, sender, content, type, attachmentId, null);
    }

    /** @param threadRootId when set, the message is a reply in that thread (one level deep only). */
    @Transactional
    public MessageResponse send(Long conversationId, User sender, String content, MessageType type,
                                Long attachmentId, Long threadRootId) {
        if (threadRootId != null) {
            Message root = messageRepository.findById(threadRootId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Thread not found"));
            if (!root.getConversation().getId().equals(conversationId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "That thread belongs to another chat");
            }
            if (root.getThreadRootId() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Replies can't have their own threads");
            }
            if (root.getDeletedAt() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This message was deleted");
            }
        }
        rateLimiter.check("msg-burst:" + sender.getId(), 15, Duration.ofSeconds(10),
                "You're sending messages too quickly. Slow down a little.");
        rateLimiter.check("msg:" + sender.getId(), 120, Duration.ofMinutes(1),
                "Message limit reached. Please wait a minute.");
        if (content != null && content.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Messages can be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        Conversation conversation = assertCanPost(conversationId, sender);
        assertCanUseGroupMention(conversation, sender, content);
        dlpService.enforceBlock(content, sender.getId(), conversationId);

        conversationService.unhideForAll(conversationId);

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
                .threadRootId(threadRootId)
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

    /**
     * Forwards a message to other chats. Attachments are re-linked as new attachment rows
     * pointing at the same stored file, so each target conversation's access check still works.
     */
    @Transactional
    public List<MessageResponse> forward(Long messageId, User sender, List<Long> targetConversationIds) {
        rateLimiter.check("fwd:" + sender.getId(), 20, Duration.ofMinutes(1),
                "You're forwarding too quickly. Please wait a moment.");
        Message source = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
        conversationService.assertMember(source.getConversation().getId(), sender.getId());

        if (source.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Deleted messages can't be forwarded");
        }
        if (source.getType() == MessageType.POLL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Polls can't be forwarded");
        }
        if (targetConversationIds == null || targetConversationIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick at least one chat");
        }

        List<Attachment> sourceAttachments = attachmentRepository.findByMessageId(source.getId());
        List<MessageResponse> results = new ArrayList<>();

        for (Long targetId : targetConversationIds.stream().distinct().toList()) {
            Conversation target = assertCanPost(targetId, sender);
            conversationService.unhideForAll(targetId);

            Message message = messageRepository.save(Message.builder()
                    .conversation(target)
                    .sender(sender)
                    .content(source.getContent())
                    .type(source.getType())
                    .forwarded(true)
                    .build());

            List<AttachmentResponse> attachmentResponses = new ArrayList<>();
            for (Attachment att : sourceAttachments) {
                Attachment copy = attachmentRepository.save(Attachment.builder()
                        .conversationId(targetId)
                        .uploadedBy(sender)
                        .message(message)
                        .fileName(att.getFileName())
                        .storedFileName(att.getStoredFileName())
                        .fileSize(att.getFileSize())
                        .mimeType(att.getMimeType())
                        .build());
                attachmentResponses.add(AttachmentResponse.from(copy));
            }

            boolean isDelivered = conversationMemberRepository.findByConversationId(targetId).stream()
                    .filter(m -> !m.getUser().getId().equals(sender.getId()))
                    .anyMatch(om -> presenceService.isOnline(om.getUser().getId()));

            results.add(MessageResponse.from(message, attachmentResponses, Collections.emptyMap(),
                    Collections.emptyList(), null, false, isDelivered));
        }
        return results;
    }

    // ── Search & mentions ───────────────────────────────────────────────

    private static String likeEscape(String s) {
        return s.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> search(Long userId, String q, Long conversationId, Long senderId,
                                        Instant from, Instant to, String has, int limit) {
        List<Long> convIds = conversationService.getConversationIdsForUser(userId);
        if (conversationId != null) {
            convIds = convIds.contains(conversationId) ? List.of(conversationId) : List.of();
        }
        if (convIds.isEmpty()) return List.of();

        String text = q == null ? "" : q.trim();
        if (text.length() > 200) text = text.substring(0, 200);
        String hasFilter = has == null ? "" : has.trim().toLowerCase();
        if (!List.of("", "link", "image", "file", "voice").contains(hasFilter)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown filter: " + has);
        }
        if (text.isEmpty() && hasFilter.isEmpty() && senderId == null && from == null && to == null) {
            return List.of(); // refuse an unfiltered dump of every message
        }

        List<Message> found = messageRepository.search(
                convIds,
                text,
                "%" + likeEscape(text) + "%",
                senderId != null ? senderId : -1L,
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.now().plus(Duration.ofDays(1)),
                hasFilter,
                PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
        return found.stream().map(m -> getSingleMessageResponse(m, userId)).toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> mentions(User user, int limit) {
        List<Long> convIds = conversationService.getConversationIdsForUser(user.getId());
        if (convIds.isEmpty()) return List.of();
        String name = likeEscape(user.getName());
        return messageRepository.findMentions(
                        convIds, user.getId(),
                        "%@" + name + "%",
                        "replying to " + name + ":%",
                        PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream()
                .map(m -> getSingleMessageResponse(m, user.getId()))
                .toList();
    }

    // ── Starred messages (per user, synced across devices) ──────────────

    @Transactional(readOnly = true)
    public List<MessageResponse> getStarred(Long userId) {
        return starredMessageRepository.findByUserIdOrderByStarredAtDesc(userId).stream()
                .map(StarredMessage::getMessage)
                .filter(m -> m.getDeletedAt() == null)
                .filter(m -> conversationMemberRepository.existsByConversationIdAndUserId(m.getConversation().getId(), userId))
                .map(m -> getSingleMessageResponse(m, userId))
                .toList();
    }

    @Transactional
    public MessageResponse star(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
        conversationService.assertMember(message.getConversation().getId(), userId);

        if (!starredMessageRepository.existsByUserIdAndMessageId(userId, messageId)) {
            starredMessageRepository.save(StarredMessage.builder().userId(userId).message(message).build());
        }
        return getSingleMessageResponse(message, userId);
    }

    @Transactional
    public void unstar(Long messageId, Long userId) {
        starredMessageRepository.findByUserIdAndMessageId(userId, messageId)
                .ifPresent(starredMessageRepository::delete);
    }

    /**
     * History, newest page first. With {@code beforeId} it returns the page of messages older than
     * that id (cursor paging, stable while new messages arrive); otherwise it uses {@code page}.
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getHistory(Long conversationId, Long requestingUserId, int page, int size, Long beforeId) {
        conversationService.assertMember(conversationId, requestingUserId);

        Pageable pageable = PageRequest.of(beforeId != null ? 0 : page, size);
        List<Message> messages = new ArrayList<>(messageRepository
                .findByConversationIdAndThreadRootIdIsNullAndIdLessThanAndCreatedAtAfterOrderByIdDesc(
                        conversationId,
                        beforeId != null ? beforeId : Long.MAX_VALUE,
                        conversationService.getVisibleSince(conversationId, requestingUserId),
                        pageable)
                .getContent());

        Collections.reverse(messages);
        return toResponses(messages, conversationId, requestingUserId);
    }

    /** Replies in a thread (oldest first), plus the root as the first element. */
    @Transactional(readOnly = true)
    public List<MessageResponse> getThread(Long conversationId, Long rootId, Long requestingUserId) {
        conversationService.assertMember(conversationId, requestingUserId);
        Message root = messageRepository.findById(rootId)
                .filter(m -> m.getConversation().getId().equals(conversationId) && m.getThreadRootId() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Thread not found"));
        List<Message> all = new ArrayList<>();
        all.add(root);
        all.addAll(messageRepository.findByThreadRootIdAndCreatedAtAfterOrderByCreatedAtAsc(
                rootId, conversationService.getVisibleSince(conversationId, requestingUserId)));
        return toResponses(all, conversationId, requestingUserId);
    }

    /** The current state of a thread root (with reply count), for live updates after a reply. */
    @Transactional(readOnly = true)
    public MessageResponse getMessageView(Long messageId, Long requestingUserId) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
        return getSingleMessageResponse(m, requestingUserId);
    }

    private Map<Long, Object[]> threadStatsFor(List<Long> rootIds) {
        if (rootIds.isEmpty()) return Collections.emptyMap();
        return messageRepository.threadStats(rootIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> row));
    }

    private MessageResponse applyThreadStats(MessageResponse r, Map<Long, Object[]> stats) {
        Object[] row = stats.get(r.id());
        return row == null ? r : r.withThreadStats(((Number) row[1]).longValue(), (Instant) row[2]);
    }

    private List<MessageResponse> toResponses(List<Message> messages, Long conversationId, Long requestingUserId) {
        List<Long> msgIds = messages.stream().map(Message::getId).toList();
        Map<Long, Object[]> threadStats = threadStatsFor(
                messages.stream().filter(m -> m.getThreadRootId() == null).map(Message::getId).toList());
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

        Map<Long, List<AttachmentResponse>> attachmentsByMsg = msgIds.isEmpty() ? Collections.emptyMap()
                : attachmentRepository.findByMessageIdIn(msgIds).stream()
                        .collect(Collectors.groupingBy(a -> a.getMessage().getId(),
                                Collectors.mapping(AttachmentResponse::from, Collectors.toList())));

        List<ConversationMember> members = conversationMemberRepository.findByConversationId(conversationId);
        List<ConversationMember> otherMembers = members.stream()
                .filter(m -> !m.getUser().getId().equals(requestingUserId))
                .toList();

        return messages.stream()
                .map(m -> {
                    List<AttachmentResponse> atts = attachmentsByMsg.getOrDefault(m.getId(), List.of());

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

                    return applyThreadStats(
                            MessageResponse.from(m, atts, reactions, details, pollResp, isRead, isDelivered), threadStats);
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

        Instant now = Instant.now();
        String updated = newContent != null ? newContent : "";
        dlpService.enforceBlock(updated, user.getId(), message.getConversation().getId());
        if (updated.equals(message.getContent())) return getSingleMessageResponse(message, user.getId());
        editRepository.save(MessageEdit.builder()
                .messageId(message.getId()).previousContent(message.getContent()).editedAt(now).build());
        message.setContent(updated);
        message.setEditedAt(now);
        messageRepository.save(message);

        return getSingleMessageResponse(message, user.getId());
    }

    @Transactional
    public MessageResponse deleteMessage(Long messageId, User user) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        boolean isSender = message.getSender().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == com.officechat.office_chat_backend.user.Role.ADMIN;

        if (!isSender && !isAdmin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to delete this message");
        }

        message.setDeletedAt(Instant.now());
        messageRepository.save(message);

        if (!isSender) {
            auditService.log(user.getId(), "MESSAGE_DELETED_BY_ADMIN", "MESSAGE", message.getId(),
                    "Deleted a message from " + message.getSender().getName()
                            + " in conversation #" + message.getConversation().getId());
        }

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

        MessageResponse response = MessageResponse.from(message, atts, reactionCounts, reactionDetails, pollResp, isRead, isDelivered);
        return message.getThreadRootId() == null
                ? applyThreadStats(response, threadStatsFor(List.of(message.getId())))
                : response;
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