package com.officechat.office_chat_backend.message;

import com.officechat.office_chat_backend.conversation.Conversation;
import com.officechat.office_chat_backend.user.User;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageType type;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private boolean isPinned = false;

    @Column(name = "forwarded")
    private Boolean forwarded; // null/false = original message

    public boolean isForwardedMessage() {
        return Boolean.TRUE.equals(forwarded);
    }

    /** Set for replies inside a thread: the id of the thread's root message. */
    @Column(name = "thread_root_id")
    private Long threadRootId;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}