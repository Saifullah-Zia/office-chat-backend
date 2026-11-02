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
        if (isProvider(currentUserId) && isProvider(otherUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Providers can only message company staff");
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
        return createGroup(creatorId, name, memberIds, null, false);
    }

    @Transactional
    public ConversationResponse createGroup(Long creatorId, String name, List<Long> memberIds,
                                            String description, boolean isPublic) {
        assertNotProvider(creatorId, "Providers can't create groups");
        Conversation conversation = conversationRepository.save(
                Conversation.builder().type(ConversationType.GROUP).name(name).createdBy(String.valueOf(creatorId))
                        .description(description != null && !description.isBlank() ? description.trim() : null)
                        .publicChannel(isPublic ? Boolean.TRUE : null)
                        .build()
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

    public boolean isMember(Long conversationId, Long userId) {
        return memberRepository.existsByConversationIdAndUserId(conversationId, userId);
    }

    /** Providers (external doctors) talk only with company staff. */
    public boolean isProvider(Long userId) {
        return userRepository.findById(userId).map(u -> u.getRole() == Role.PROVIDER).orElse(false);
    }

    private void assertNotProvider(Long userId, String message) {
        if (isProvider(userId)) throw new ApiException(HttpStatus.FORBIDDEN, message);
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

    @Transactional(readOnly = true)
    public List<ConversationResponse> getUserConversations(Long userId) {
        List<Conversation> conversations = memberRepository.findWithConversationByUserId(userId).stream()
                .filter(m -> !m.isHiddenForMember())
                .map(ConversationMember::getConversation)
                .toList();
        ListContext ctx = loadContext(conversations.stream().map(Conversation::getId).toList(), userId);
        return conversations.stream().map(c -> toResponse(c, userId, ctx)).toList();
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

    /** All member user emails, for per-user STOMP notifications. Transactional: also called from schedulers. */
    @Transactional(readOnly = true)
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

    private boolean isGlobalAdmin(Long userId) {
        return userRepository.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }

    // ── Visibility: clear-chat point + disappearing messages ──────────────

    @org.springframework.beans.factory.annotation.Value("${app.retention.max-days:0}")
    private int orgMaxRetentionDays;

    /** Messages created at or before this instant are hidden from this member. */
    public Instant getVisibleSince(Long conversationId, Long userId) {
        Instant since = Instant.EPOCH;
        Instant cleared = getClearedAt(conversationId, userId);
        if (cleared != null) since = cleared;
        Integer days = conversationRepository.findById(conversationId).map(Conversation::getRetentionDays).orElse(null);
        int effective = effectiveRetentionDays(days);
        if (effective > 0) {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(effective));
            if (cutoff.isAfter(since)) since = cutoff;
        }
        return since;
    }

    /** The stricter of the chat's own setting and the organisation-wide maximum (0 = none). */
    public int effectiveRetentionDays(Integer chatDays) {
        int chat = chatDays != null && chatDays > 0 ? chatDays : 0;
        int org = Math.max(orgMaxRetentionDays, 0);
        if (chat == 0) return org;
        if (org == 0) return chat;
        return Math.min(chat, org);
    }

    private static final java.util.Set<Integer> ALLOWED_RETENTION = java.util.Set.of(0, 1, 7, 30, 90);

    /** Direct chats: either person may change it. Groups: admins only. */
    @Transactional
    public ConversationResponse setRetention(Long conversationId, Long requesterId, Integer days) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (conversation.getType() == ConversationType.GROUP) assertConversationAdmin(conversationId, requesterId);
        else assertMember(conversationId, requesterId);

        int d = days == null ? 0 : days;
        if (!ALLOWED_RETENTION.contains(d)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose off, 1, 7, 30 or 90 days");
        }
        conversation.setRetentionDays(d == 0 ? null : d);
        conversationRepository.save(conversation);
        auditService.log(requesterId, "RETENTION_CHANGED", "CONVERSATION", conversationId,
                (d == 0 ? "Turned off disappearing messages" : "Set disappearing messages to " + d + " day(s)")
                        + (conversation.getType() == ConversationType.GROUP ? " in " + groupLabel(conversation) : ""));
        return toResponse(conversation, requesterId);
    }

    // ── Group details & public channels ───────────────────────────────────

    /** Body fields are optional: name, description, isPublic. Admins only. */
    @Transactional
    public ConversationResponse updateDetails(Long conversationId, Long requesterId, String name, String description, Boolean isPublic) {
        assertConversationAdmin(conversationId, requesterId);
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        if (c.getType() != ConversationType.GROUP) throw new ApiException(HttpStatus.BAD_REQUEST, "Only groups have details");
        if (name != null) {
            String n = name.trim();
            if (n.isEmpty() || n.length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Name must be 1–100 characters");
            c.setName(n);
        }
        if (description != null) {
            String d = description.trim();
            if (d.length() > 1000) throw new ApiException(HttpStatus.BAD_REQUEST, "Description is too long");
            c.setDescription(d.isEmpty() ? null : d);
        }
        if (isPublic != null) c.setPublicChannel(isPublic ? Boolean.TRUE : null);
        conversationRepository.save(c);
        auditService.log(requesterId, "GROUP_DETAILS_CHANGED", "CONVERSATION", conversationId,
                "Updated " + groupLabel(c) + (isPublic != null ? " (public channel: " + isPublic + ")" : ""));
        return toResponse(c, requesterId);
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> listPublicChannels(Long userId, String query) {
        if (isProvider(userId)) return List.of(); // providers don't browse the company's channels
        String q = query == null ? "" : query.trim().toLowerCase();
        return conversationRepository.findByTypeAndPublicChannelTrueOrderByNameAsc(ConversationType.GROUP).stream()
                .filter(c -> q.isEmpty()
                        || (c.getName() != null && c.getName().toLowerCase().contains(q))
                        || (c.getDescription() != null && c.getDescription().toLowerCase().contains(q)))
                .map(c -> {
                    java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
                    v.put("id", c.getId());
                    v.put("name", c.getName());
                    v.put("description", c.getDescription());
                    v.put("memberCount", memberRepository.findByConversationId(c.getId()).size());
                    v.put("isMember", memberRepository.existsByConversationIdAndUserId(c.getId(), userId));
                    return v;
                })
                .toList();
    }

    @Transactional
    public ConversationResponse joinPublicChannel(Long conversationId, Long userId) {
        assertNotProvider(userId, "Providers can't join channels themselves; ask an admin to add you");
        Conversation c = conversationRepository.findById(conversationId)
                .filter(Conversation::isPublicChannel)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (!memberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            addMember(c, userId);
            auditService.log(userId, "MEMBER_JOINED", "CONVERSATION", conversationId, "Joined public channel " + groupLabel(c));
        }
        return toResponse(c, userId);
    }

    // ── Invite links ──────────────────────────────────────────────────────

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String newInviteCode() {
        byte[] bytes = new byte[18]; // 144 bits → 24 URL-safe chars, not guessable
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Creates (or replaces, invalidating the old link) the group's invite code. Admins only. */
    @Transactional
    public ConversationResponse resetInviteLink(Long conversationId, Long requesterId) {
        assertConversationAdmin(conversationId, requesterId);
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        if (c.getType() != ConversationType.GROUP) throw new ApiException(HttpStatus.BAD_REQUEST, "Only groups have invite links");
        c.setInviteCode(newInviteCode());
        conversationRepository.save(c);
        auditService.log(requesterId, "INVITE_LINK_RESET", "CONVERSATION", conversationId, "New invite link for " + groupLabel(c));
        return toResponse(c, requesterId);
    }

    @Transactional
    public ConversationResponse disableInviteLink(Long conversationId, Long requesterId) {
        assertConversationAdmin(conversationId, requesterId);
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        c.setInviteCode(null);
        conversationRepository.save(c);
        auditService.log(requesterId, "INVITE_LINK_DISABLED", "CONVERSATION", conversationId, "Disabled invite link for " + groupLabel(c));
        return toResponse(c, requesterId);
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> previewInvite(String code, Long userId) {
        assertNotProvider(userId, "Invite links aren't available to providers; ask an admin to add you");
        Conversation c = findByInvite(code);
        java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("conversationId", c.getId());
        v.put("name", c.getName());
        v.put("description", c.getDescription());
        v.put("memberCount", memberRepository.findByConversationId(c.getId()).size());
        v.put("isMember", memberRepository.existsByConversationIdAndUserId(c.getId(), userId));
        return v;
    }

    @Transactional
    public ConversationResponse joinByInvite(String code, Long userId) {
        assertNotProvider(userId, "Invite links aren't available to providers; ask an admin to add you");
        Conversation c = findByInvite(code);
        if (!memberRepository.existsByConversationIdAndUserId(c.getId(), userId)) {
            addMember(c, userId);
            auditService.log(userId, "MEMBER_JOINED", "CONVERSATION", c.getId(), "Joined " + groupLabel(c) + " via invite link");
        }
        return toResponse(c, userId);
    }

    private Conversation findByInvite(String code) {
        if (code == null || code.isBlank() || code.length() > 40) {
            throw new ApiException(HttpStatus.NOT_FOUND, "This invite link is invalid or has been reset");
        }
        return conversationRepository.findByInviteCode(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This invite link is invalid or has been reset"));
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
        return toResponse(conversation, currentUserId, loadContext(List.of(conversation.getId()), currentUserId));
    }

    /** What toResponse needs for a set of chats, loaded in a handful of queries instead of several per chat. */
    private record ListContext(
            java.util.Map<Long, List<ConversationMember>> membersByConv,
            java.util.Map<Long, Message> lastMessageByConv,
            java.util.Map<Long, List<com.officechat.office_chat_backend.message.MessageReaction>> reactionsByMsg,
            java.util.Map<Long, Long> unreadByConv,
            java.util.Set<Long> blockedByMe,
            java.util.Set<Long> blockedMe,
            boolean globalAdmin
    ) {}

    private ListContext loadContext(List<Long> convIds, Long userId) {
        if (convIds.isEmpty()) {
            return new ListContext(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    java.util.Set.of(), java.util.Set.of(), false);
        }
        var membersByConv = memberRepository.findWithUsersByConversationIdIn(convIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(m -> m.getConversation().getId()));
        var lastByConv = messageRepository.findLatestIn(convIds).stream()
                .collect(java.util.stream.Collectors.toMap(m -> m.getConversation().getId(), m -> m, (a, b) -> a));
        List<Long> lastIds = lastByConv.values().stream().map(Message::getId).toList();
        var reactionsByMsg = lastIds.isEmpty()
                ? java.util.Map.<Long, List<com.officechat.office_chat_backend.message.MessageReaction>>of()
                : reactionRepository.findByMessageIdIn(lastIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(r -> r.getMessage().getId()));
        java.util.Map<Long, Long> unread = new java.util.HashMap<>();
        for (Object[] row : messageRepository.unreadCounts(userId, convIds)) {
            unread.put((Long) row[0], ((Number) row[1]).longValue());
        }
        var blockedByMe = blockRepository.findByBlockerId(userId).stream()
                .map(UserBlock::getBlockedId).collect(java.util.stream.Collectors.toSet());
        var blockedMe = blockRepository.findByBlockedId(userId).stream()
                .map(UserBlock::getBlockerId).collect(java.util.stream.Collectors.toSet());
        return new ListContext(membersByConv, lastByConv, reactionsByMsg, unread, blockedByMe, blockedMe, isGlobalAdmin(userId));
    }

    private ConversationResponse toResponse(Conversation conversation, Long currentUserId, ListContext ctx) {
        List<ConversationMember> memberRows = ctx.membersByConv().getOrDefault(conversation.getId(), List.of());
        List<UserResponse> members = memberRows.stream()
                .map(ConversationMember::getUser)
                .map(u -> UserResponse.from(u).visibleTo(ctx.globalAdmin(), currentUserId))
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
        boolean staff = currentMember == null || currentMember.getUser().getRole() != Role.PROVIDER;

        var lastMessage = java.util.Optional.ofNullable(ctx.lastMessageByConv().get(conversation.getId()))
                .filter(m -> clearedAt == null || m.getCreatedAt().isAfter(clearedAt));
        String preview = null;
        Instant lastAt = null;

        if (lastMessage.isPresent()) {
            Message m = lastMessage.get();
            lastAt = m.getCreatedAt();
            if (m.getDeletedAt() != null) {
                preview = "🚫 This message was deleted";
            } else {
                List<com.officechat.office_chat_backend.message.MessageReaction> reactions = ctx.reactionsByMsg().getOrDefault(m.getId(), List.of());
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
            unreadCount = ctx.unreadByConv().getOrDefault(conversation.getId(), 0L);
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
                blockedByMe = ctx.blockedByMe().contains(otherId);
                blockedMe = ctx.blockedMe().contains(otherId);
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
                currentMember != null && currentMember.isArchivedForMember(),
                conversation.getDescription(),
                conversation.isPublicChannel(),
                conversation.getRetentionDays(),
                currentMember != null && (currentMember.isAdmin() || ctx.globalAdmin())
                        ? conversation.getInviteCode() : null,
                // Internal billing workflow: providers (external doctors) don't see it.
                staff ? splitLabels(conversation.getLabels()) : List.of(),
                staff ? conversation.getTicketStatus() : null,
                staff && conversation.getTicketUpdatedBy() != null
                        ? memberRows.stream().map(ConversationMember::getUser)
                            .filter(u -> u.getId().equals(conversation.getTicketUpdatedBy()))
                            .map(User::getName).findFirst().orElseGet(() -> userName(conversation.getTicketUpdatedBy()))
                        : null,
                staff ? conversation.getTicketUpdatedAt() : null
        );
    }

    // ── Labels & status (chat as a mini ticket) ───────────────────────────

    private static final java.util.Set<String> TICKET_STATUSES = java.util.Set.of("OPEN", "DONE");
    private static final int MAX_LABELS = 6;
    private static final int MAX_LABEL_LENGTH = 30;

    private static List<String> splitLabels(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split("\\|")).filter(s -> !s.isBlank()).toList();
    }

    /** Any staff member of the chat may set its labels and status; everyone in it sees the change live. */
    @Transactional
    public ConversationResponse setTicket(Long conversationId, Long requesterId, List<String> labels, String status) {
        assertMember(conversationId, requesterId);
        assertNotProvider(requesterId, "Only staff can change labels and status");
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        java.util.LinkedHashMap<String, String> clean = new java.util.LinkedHashMap<>(); // lower-case → as typed
        for (String l : labels == null ? List.<String>of() : labels) {
            if (l == null) continue;
            String t = l.trim().replaceAll("\\s+", " ").replace("|", "/");
            if (t.isEmpty()) continue;
            if (t.length() > MAX_LABEL_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Labels can be at most " + MAX_LABEL_LENGTH + " characters");
            }
            clean.putIfAbsent(t.toLowerCase(), t);
        }
        if (clean.size() > MAX_LABELS) throw new ApiException(HttpStatus.BAD_REQUEST, "A chat can have at most " + MAX_LABELS + " labels");
        String next = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (next != null && !TICKET_STATUSES.contains(next)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Status must be Open, Done or none");
        }

        String newLabels = clean.isEmpty() ? null : String.join("|", clean.values());
        boolean changed = !java.util.Objects.equals(newLabels, c.getLabels()) || !java.util.Objects.equals(next, c.getTicketStatus());
        if (!changed) return toResponse(c, requesterId);

        String before = (c.getTicketStatus() == null ? "no status" : c.getTicketStatus()) + " " + splitLabels(c.getLabels());
        c.setLabels(newLabels);
        c.setTicketStatus(next);
        c.setTicketUpdatedBy(requesterId);
        c.setTicketUpdatedAt(Instant.now());
        conversationRepository.save(c);
        auditService.log(requesterId, "CHAT_TICKET_CHANGED", "CONVERSATION", conversationId,
                (c.getType() == ConversationType.GROUP ? groupLabel(c) + ": " : "")
                        + before + " → " + (next == null ? "no status" : next) + " " + splitLabels(newLabels));
        return toResponse(c, requesterId);
    }

    /** Staff members of the chat (the ones who see labels), for live updates. */
    @Transactional(readOnly = true)
    public List<String> getStaffMemberEmails(Long conversationId) {
        return memberRepository.findByConversationId(conversationId).stream()
                .map(ConversationMember::getUser)
                .filter(u -> u.getRole() != Role.PROVIDER)
                .map(User::getEmail)
                .toList();
    }
}
