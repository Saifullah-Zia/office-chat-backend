package com.officechat.office_chat_backend.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    /**
     * Cursor page of the main timeline (thread replies excluded): messages older than {@code beforeId}
     * and newer than {@code after} (the member's clear point / retention cutoff).
     */
    Page<Message> findByConversationIdAndThreadRootIdIsNullAndIdLessThanAndCreatedAtAfterOrderByIdDesc(
            Long conversationId, Long beforeId, Instant after, Pageable pageable);

    /** Replies in a thread, oldest first. */
    List<Message> findByThreadRootIdAndCreatedAtAfterOrderByCreatedAtAsc(Long threadRootId, Instant after);

    // Export paging (keyset by id, oldest first)
    List<Message> findByConversationIdAndIdGreaterThanOrderByIdAsc(Long conversationId, Long afterId, Pageable pageable);

    List<Message> findBySenderIdAndIdGreaterThanOrderByIdAsc(Long senderId, Long afterId, Pageable pageable);

    /** [rootId, replyCount, lastReplyAt] for the given thread roots. */
    @Query("""
            select m.threadRootId, count(m), max(m.createdAt) from Message m
            where m.threadRootId in :rootIds and m.deletedAt is null
            group by m.threadRootId
            """)
    List<Object[]> threadStats(@Param("rootIds") Collection<Long> rootIds);

    /** Disappearing messages: blank out content of messages older than the cutoff. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Message m set m.content = '', m.deletedAt = coalesce(m.deletedAt, :now)
            where m.conversation.id = :conversationId and m.createdAt < :cutoff
              and (m.content <> '' or m.deletedAt is null)
            """)
    int scrubOlderThan(@Param("conversationId") Long conversationId, @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    Optional<Message> findTopByConversationIdOrderByCreatedAtDesc(Long conversationId);

    long countByConversationIdAndCreatedAtAfter(Long conversationId, Instant after);

    /** The newest message of each given conversation (ids are assigned in send order). */
    @Query("""
            select m from Message m
            where m.conversation.id in :convIds
              and m.id = (select max(m2.id) from Message m2 where m2.conversation.id = m.conversation.id)
            """)
    List<Message> findLatestIn(@Param("convIds") Collection<Long> convIds);

    /** [conversationId, unreadCount] for this user, counting messages after their last read (or join) time. */
    @Query("""
            select cm.conversation.id, count(m) from ConversationMember cm, Message m
            where cm.user.id = :userId and cm.conversation.id in :convIds
              and m.conversation = cm.conversation
              and m.createdAt > coalesce(cm.lastReadAt, cm.joinedAt)
            group by cm.conversation.id
            """)
    List<Object[]> unreadCounts(@Param("userId") Long userId, @Param("convIds") Collection<Long> convIds);

    /**
     * Server-side message search across the given conversations.
     * Sentinels instead of nulls (Postgres can't infer types for null params):
     * q = '' (no text filter), senderId = -1 (any sender), has = '' | link | image | file | voice.
     */
    @Query("""
            select m from Message m
            where m.conversation.id in :convIds
              and m.deletedAt is null
              and (:q = '' or lower(m.content) like :qLike escape '\\')
              and (:senderId = -1 or m.sender.id = :senderId)
              and m.createdAt >= :from and m.createdAt <= :to
              and (:has = ''
                   or (:has = 'link' and lower(m.content) like '%http%')
                   or (:has = 'image' and exists (select a.id from Attachment a
                         where a.message = m and a.mimeType like 'image/%'))
                   or (:has = 'file' and exists (select a.id from Attachment a
                         where a.message = m and (a.mimeType is null
                           or (a.mimeType not like 'image/%' and a.mimeType not like 'audio/%'))))
                   or (:has = 'voice' and (m.type = com.officechat.office_chat_backend.message.MessageType.VOICE
                         or m.type = com.officechat.office_chat_backend.message.MessageType.AUDIO)))
            order by m.createdAt desc
            """)
    List<Message> search(@Param("convIds") Collection<Long> convIds,
                         @Param("q") String q,
                         @Param("qLike") String qLike,
                         @Param("senderId") Long senderId,
                         @Param("from") Instant from,
                         @Param("to") Instant to,
                         @Param("has") String has,
                         Pageable pageable);

    /** Messages that @mention the user by name, @all/@here/@channel in groups, or reply to them. */
    @Query("""
            select m from Message m
            where m.conversation.id in :convIds
              and m.sender.id <> :userId
              and m.deletedAt is null
              and (lower(m.content) like :mentionLike escape '\\'
                   or lower(m.content) like :replyLike escape '\\'
                   or (m.conversation.type = com.officechat.office_chat_backend.conversation.ConversationType.GROUP
                       and (lower(m.content) like '%@all%' or lower(m.content) like '%@here%'
                            or lower(m.content) like '%@channel%')))
            order by m.createdAt desc
            """)
    List<Message> findMentions(@Param("convIds") Collection<Long> convIds,
                               @Param("userId") Long userId,
                               @Param("mentionLike") String mentionLike,
                               @Param("replyLike") String replyLike,
                               Pageable pageable);
}
