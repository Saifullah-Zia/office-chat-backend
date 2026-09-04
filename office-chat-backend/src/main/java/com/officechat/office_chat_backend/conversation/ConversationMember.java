package com.officechat.office_chat_backend.conversation;

import com.officechat.office_chat_backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "conversation_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at", updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_at")
    private Instant lastReadAt; // null = never read anything yet

    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "is_admin", nullable = false)
    private boolean isAdmin;

    @PrePersist
    protected void onJoin() {
        this.joinedAt = Instant.now();
        this.lastReadAt = this.joinedAt; // don't count pre-join history as unread
    }
}