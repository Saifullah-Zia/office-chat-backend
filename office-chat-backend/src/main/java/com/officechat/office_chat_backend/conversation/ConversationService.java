package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.dto.ConversationResponse;
import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.message.MessageRepository;
import com.officechat.office_chat_backend.message.MessageReactionRepository;
import com.officechat.office_chat_backend.user.Role;
import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserBlock;
import com.officechat.office_chat_backend.user.UserBlockRepository;
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
    private final UserBlockRepository blockRepository;
    private final com.officechat.office_chat_backend.audit.AuditService auditService;

    private String userName(Long userId) {
        return userRepository.findById(userId).map(User::getName).orElse("User #" + userId);
    }

    private String groupLabel(Conversation c) {
        return "\"" + (c.getName() != null ? c.getName() : "Group #" + c.getId()) + "\"";
    }

    @Transactional
    public ConversationResponse getOrCreateDirect(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot start a conversation with yourself");
        }

        List<Long> existingIds = memberRepository.findDirectConversationIdsBetween(currentUserId, otherUserId);
        if (!existingIds.isEmpty()) {
            // Re-opening a chat the user previously deleted brings it back to their list.
            memberRepository.findByConversationIdAndUserId(existingIds.get(0), currentUserId)
                    .filter(ConversationMember::isHiddenForMember)
                    .ifPresent(m -> {
                        m.setHidden(false);
                        memberRepository.save(m);
                    });
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

        auditService.log(creatorId, "GROUP_CREATED", "CONVERSATION", conversation.getId(),
                "Created group " + groupLabel(conversation));
        return toResponse(conversation, creatorId);
    }

    public void assertMember(Long conversationId, Long userId) {
        if (!memberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation");
        }
    }

    /** Group admin = member flagged is_admin, or a global ADMIN account. */
    public boolean isConversationAdmin(Long conversationId, Long userId) {
        boolean memberAdmin = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .map(ConversationMember::isAdmin)
                .orElse(false);
        if (memberAdmin) return true;
        return userRepository.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }

    private void assertConversationAdmin(Long conversationId, Long userId) {
        assertMember(conversationId, userId);
        if (!isConversationAdmin(conversationId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only group admins can do this");
        }
    }

    /** True if either user in a DIRECT conversation has blocked the other. */
    public boolean isBlockedBetween(Long userA, Long userB) {
        return blockRepository.existsByBlockerIdAndBlockedId(userA, userB)
                || blockRepository.existsByBlockerIdAndBlockedId(userB, userA);
    }

    public List<ConversationResponse> getUserConversations(Long userId) {
        return memberRepository.findByUserId(userId).stream()
                .filter(m -> !m.isHiddenForMember())
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
        assertConversationAdmin(conversationId, requesterId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));

        if (!memberRepository.existsByConversationIdAndUserId(conversationId, newUserId)) {
            addMember(conversation, newUserId);
            auditService.log(requesterId, "MEMBER_ADDED", "CONVERSATION", conversationId,
                    "Added " + userName(newUserId) + " to " + groupLabel(conversation));
        }
        return toResponse(conversation, requesterId);
    }

    /** Admins can remove anyone; any member can remove themselves (exit group). */
    @Transactional
    public ConversationResponse removeMemberFromGroup(Long conversationId, Long requesterId, Long targetUserId) {
        if (requesterId.equals(targetUserId)) {
            assertMember(conversationId, requesterId);
        } else {
            assertConversationAdmin(conversationId, requesterId);
        }
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found in group"));

        memberRepository.delete(member);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        if (requesterId.equals(targetUserId)) {
            auditService.log(requesterId, "MEMBER_LEFT", "CONVERSATION", conversationId,
                    "Left " + groupLabel(conversation));
        } else {
            auditService.log(requesterId, "MEMBER_REMOVED", "CONVERSATION", conversationId,
                    "Removed " + userName(targetUserId) + " from " + groupLabel(conversation));
        }
        return toResponse(conversation, requesterId);
    }

    @Transactional
    public ConversationResponse setMemberAdmin(Long conversationId, Long requesterId, Long targetUserId, boolean admin) {
        assertConversationAdmin(conversationId, requesterId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        if (conversation.getType() != ConversationType.GROUP) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Admins only exist in groups");
        }

        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found in group"));
        member.setAdmin(admin);
        memberRepository.save(member);

        auditService.log(requesterId, admin ? "GROUP_ADMIN_GRANTED" : "GROUP_ADMIN_REVOKED", "CONVERSATION", conversationId,
                (admin ? "Made " : "Dismissed ") + userName(targetUserId) + (admin ? " an admin of " : " as admin of ")
                        + groupLabel(conversation));
        return toResponse(conversation, requesterId);
    }

    /** "Clear chat": hides all current messages for this member only. */
    @Transactional
    public ConversationResponse clearChat(Long conversationId, Long userId) {
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation"));
        Instant now = Instant.now();
        member.setClearedAt(now);
        member.setLastReadAt(now);
        memberRepository.save(member);
        return toResponse(member.getConversation(), userId);
    }

    /** "Delete chat": clears it and removes it from this member's list until a new message arrives. */
    @Transactional
    public void deleteChatForMe(Long conversationId, Long userId) {
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation"));
        Instant now = Instant.now();
        member.setClearedAt(now);
        member.setLastReadAt(now);
        member.setHidden(true);
        memberRepository.save(member);
    }

    /** Called when a new message is sent so deleted chats reappear for everyone. */
    @Transactional
    public void unhideForAll(Long conversationId) {
        memberRepository.findByConversationId(conversationId).stream()
                .filter(ConversationMember::isHiddenForMember)
                .forEach(m -> {
                    m.setHidden(false);
                    memberRepository.save(m);
                });
    }

    @Transactional
    public ConversationResponse setPinned(Long conversationId, Long userId, boolean pinned) {
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation"));
        if (pinned && !member.isPinnedForMember()) {
            long pinnedCount = memberRepository.findByUserId(userId).stream()
                    .filter(ConversationMember::isPinnedForMember).count();
            if (pinnedCount >= 5) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You can pin up to 5 chats");
            }
        }
        member.setPinned(pinned);
        memberRepository.save(member);
        return toResponse(member.getConversation(), userId);
    }

    @Transactional
    public ConversationResponse setArchived(Long conversationId, Long userId, boolean archived) {
        ConversationMember member = memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of this conversation"));
        member.setArchived(archived);
        if (archived) member.setPinned(false); // an archived chat can't stay pinned
        memberRepository.save(member);
        return toResponse(member.getConversation(), userId);
    }

    /** Who has read a message: a member has read it if their last-read time is at/after it. */
    public List<java.util.Map<String, Object>> getReadReceipts(Long conversationId, Long messageId, Long requesterId) {
        assertMember(conversationId, requesterId);
        Message message = messageRepository.findById(messageId)
                .filter(m -> m.getConversation().getId().equals(conversationId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));

        return memberRepository.findByConversationId(conversationId).stream()
                .filter(m -> !m.getUser().getId().equals(message.getSender().getId()))
                .map(m -> {
                    boolean read = m.getLastReadAt() != null && !m.getLastReadAt().isBefore(message.getCreatedAt());
                    java.util.Map<String, Object> row = new java.util.HashMap<>();
                    row.put("userId", m.getUser().getId());
                    row.put("name", m.getUser().getName());
                    row.put("read", read);
                    row.put("readAt", read ? m.getLastReadAt() : null);
                    return row;
                })
                .toList();
    }

    /** All member user emails, for per-user STOMP notifications. */
    public List<String> getMemberEmails(Long conversationId) {
        return memberRepository.findByConversationId(conversationId).stream()
                .map(m -> m.getUser().getEmail())
                .toList();
    }

    public List<Long> getConversationIdsForUser(Long userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(m -> m.getConversation().getId())
                .toList();
    }

    public Instant getClearedAt(Long conversationId, Long userId) {
        return memberRepository.findByConversationIdAndUserId(conversationId, userId)
                .map(ConversationMember::getClearedAt)
                .orElse(null);
    }

    @Transactional
    public void blockUser(Long blockerId, Long blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot block yourself");
        }
        userRepository.findById(blockedId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!blockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            blockRepository.save(UserBlock.builder().blockerId(blockerId).blockedId(blockedId).build());
        }
    }

    @Transactional
    public void unblockUser(Long blockerId, Long blockedId) {
        blockRepository.findByBlockerIdAndBlockedId(blockerId, blockedId).ifPresent(blockRepository::delete);
    }

    public List<Long> getBlockedUserIds(Long blockerId) {
        return blockRepository.findByBlockerId(blockerId).stream().map(UserBlock::getBlockedId).toList();
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
        assertConversationAdmin(conversationId, requesterId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        conversation.setOnlyAdminsCanPost(onlyAdminsCanPost);
        conversation.setAnnouncement(isAnnouncement);
        conversationRepository.save(conversation);

        auditService.log(requesterId, "GROUP_SETTINGS_CHANGED", "CONVERSATION", conversationId,
                groupLabel(conversation) + ": only admins can post = " + onlyAdminsCanPost
                        + ", announcement = " + isAnnouncement);

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

    public ConversationResponse getConversation(Long conversationId, Long userId) {
        assertMember(conversationId, userId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return toResponse(conversation, userId);
    }

    private ConversationResponse toResponse(Conversation conversation, Long currentUserId) {
        List<ConversationMember> memberRows = memberRepository.findByConversationId(conversation.getId());
        List<UserResponse> members = memberRows.stream()
                .map(ConversationMember::getUser)
                .map(UserResponse::from)
                .toList();
        List<Long> adminIds = memberRows.stream()
                .filter(ConversationMember::isAdmin)
                .map(m -> m.getUser().getId())
                .toList();

        ConversationMember currentMember = memberRows.stream()
                .filter(m -> m.getUser().getId().equals(currentUserId))
                .findFirst()
                .orElse(null);
        Instant clearedAt = currentMember != null ? currentMember.getClearedAt() : null;

        var lastMessage = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId())
                .filter(m -> clearedAt == null || m.getCreatedAt().isAfter(clearedAt));
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

        long unreadCount = 0;
        Instant mutedUntil = null;
        if (currentMember != null) {
            Instant since = currentMember.getLastReadAt() != null ? currentMember.getLastReadAt() : currentMember.getJoinedAt();
            unreadCount = messageRepository.countByConversationIdAndCreatedAtAfter(conversation.getId(), since);
            mutedUntil = currentMember.getMutedUntil();
        }

        boolean blockedByMe = false;
        boolean blockedMe = false;
        if (conversation.getType() == ConversationType.DIRECT) {
            Long otherId = memberRows.stream()
                    .map(m -> m.getUser().getId())
                    .filter(id -> !id.equals(currentUserId))
                    .findFirst()
                    .orElse(null);
            if (otherId != null) {
                blockedByMe = blockRepository.existsByBlockerIdAndBlockedId(currentUserId, otherId);
                blockedMe = blockRepository.existsByBlockerIdAndBlockedId(otherId, currentUserId);
            }
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
                mutedUntil,
                adminIds,
                blockedByMe,
                blockedMe,
                currentMember != null && currentMember.isPinnedForMember(),
                currentMember != null && currentMember.isArchivedForMember()
        );
    }
}
