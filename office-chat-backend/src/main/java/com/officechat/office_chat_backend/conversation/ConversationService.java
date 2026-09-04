package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.dto.ConversationResponse;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.message.MessageReactionRepository;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserRepository;
import com.officechat.office_chat_backend.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final UserRepository userRepository;

    @Transactional
    public ConversationResponse getOrCreateDirect(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot start a conversation with yourself");
        }

        List<Long> existingIds = memberRepository.findDirectConversationIdsBetween(currentUserId, otherUserId);
        if (!existingIds.isEmpty()) {
            return toResponse(conversationRepository.findById(existingIds.get(0)).orElseThrow(), currentUserId);
        }

        Conversation conversation = conversationRepository.save(
                Conversation.builder().type(ConversationType.DIRECT).createdBy(String.valueOf(currentUserId)).build()
        );

        addMember(conversation, currentUserId);
        addMember(conversation, otherUserId);

        return toResponse(conversation, currentUserId);
    }

    @Transactional
    public ConversationResponse createGroup(Long creatorId, String name, List<Long> memberIds) {
        Conversation conversation = conversationRepository.save(
                Conversation.builder().type(ConversationType.GROUP).name(name).createdBy(String.valueOf(creatorId)).build()
        );

        addMember(conversation, creatorId);
        memberIds.stream()
                .distinct()
                .filter(id -> !id.equals(creatorId))
                .forEach(id -> addMember(conversation, id));

        return toResponse(conversation, creatorId);
    }

    public void assertMember(Long conversationId, Long userId) {
        if (!memberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation");
        }
    }

    public List<ConversationResponse> getUserConversations(Long userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(ConversationMember::getConversation)
                .map(conversation -> toResponse(conversation, userId))
                .toList();
    }

    @Transactional
    public void markAsRead(Long conversationId, Long userId) {
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation"));

        member.setLastReadAt(Instant.now());
        memberRepository.save(member);
    }

    @Transactional
    public ConversationResponse addMemberToGroup(Long conversationId, Long requesterId, Long newUserId) {
        assertMember(conversationId, requesterId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));

        if (!memberRepository.existsByConversationIdAndUserId(conversationId, newUserId)) {
            addMember(conversation, newUserId);
        }
        return toResponse(conversation, requesterId);
    }

    @Transactional
    public ConversationResponse removeMemberFromGroup(Long conversationId, Long requesterId, Long targetUserId) {
        assertMember(conversationId, requesterId);
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found in group"));

        memberRepository.delete(member);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        return toResponse(conversation, requesterId);
    }

    private void addMember(Conversation conversation, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User " + userId + " not found"));

        // The user who created the conversation is automatically an admin of it.
        boolean isCreator = String.valueOf(userId).equals(conversation.getCreatedBy());

        memberRepository.save(ConversationMember.builder()
                .conversation(conversation)
                .user(user)
                .isAdmin(isCreator)
                .build());
    }

    @Transactional
    public ConversationResponse updatePermissions(Long conversationId, Long requesterId, boolean onlyAdminsCanPost, boolean isAnnouncement) {
        assertMember(conversationId, requesterId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, requesterId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Not a member"));

        // Per-group admin check (not the user's global account role).
        if (!member.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can change conversation permissions");
        }

        conversation.setOnlyAdminsCanPost(onlyAdminsCanPost);
        conversation.setAnnouncement(isAnnouncement);
        conversationRepository.save(conversation);

        return toResponse(conversation, requesterId);
    }

    @Transactional
    public ConversationResponse muteConversation(Long conversationId, Long userId, Integer durationHours) {
        assertMember(conversationId, userId);
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));

        if (durationHours == null || durationHours <= 0) {
            member.setMutedUntil(null); // Unmute
        } else if (durationHours >= 9999) {
            member.setMutedUntil(Instant.now().plus(java.time.Duration.ofDays(3650))); // Mute always
        } else {
            member.setMutedUntil(Instant.now().plus(java.time.Duration.ofHours(durationHours)));
        }

        memberRepository.save(member);

        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        return toResponse(conversation, userId);
    }

    private ConversationResponse toResponse(Conversation conversation, Long currentUserId) {
        List<UserResponse> members = memberRepository.findByConversationId(conversation.getId()).stream()
                .map(ConversationMember::getUser)
                .map(UserResponse::from)
                .toList();

        var lastMessage = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId());
        String preview = null;
        Instant lastAt = null;

        if (lastMessage.isPresent()) {
            Message m = lastMessage.get();
            lastAt = m.getCreatedAt();
            if (m.getDeletedAt() != null) {
                preview = "🚫 This message was deleted";
            } else {
                List<com.officechat.office_chat_backend.message.MessageReaction> reactions = reactionRepository.findByMessageId(m.getId());
                if (!reactions.isEmpty()) {
                    var latest = reactions.get(reactions.size() - 1);
                    String name = latest.getUser().getId().equals(currentUserId) ? "You" : latest.getUser().getName();
                    String content = m.getContent() != null ? m.getContent() : "";
                    String snippet = content.length() > 22 ? content.substring(0, 22) + "..." : content;
                    preview = latest.getEmoji() + " " + name + " reacted to \"" + snippet + "\"";
                } else if (m.getType() == com.officechat.office_chat_backend.message.MessageType.VOICE || m.getType() == com.officechat.office_chat_backend.message.MessageType.AUDIO) {
                    preview = "🎤 Voice note";
                } else if (m.getType() == com.officechat.office_chat_backend.message.MessageType.IMAGE) {
                    preview = "📷 Photo";
                } else if (m.getType() == com.officechat.office_chat_backend.message.MessageType.FILE) {
                    preview = "📁 File";
                } else {
                    preview = m.getContent();
                }
            }
        }

        ConversationMember currentMember = memberRepository
                .findByConversationIdAndUserId(conversation.getId(), currentUserId)
                .orElse(null);

        long unreadCount = 0;
        Instant mutedUntil = null;
        if (currentMember != null) {
            Instant since = currentMember.getLastReadAt() != null ? currentMember.getLastReadAt() : currentMember.getJoinedAt();
            unreadCount = messageRepository.countByConversationIdAndCreatedAtAfter(conversation.getId(), since);
            mutedUntil = currentMember.getMutedUntil();
        }

        return new ConversationResponse(
                conversation.getId(),
                conversation.getType(),
                conversation.getName(),
                members,
                preview,
                lastAt,
                unreadCount,
                conversation.isOnlyAdminsCanPost(),
                conversation.isAnnouncement(),
                mutedUntil
        );
    }
}