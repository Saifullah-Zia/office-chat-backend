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

    @Column(name = "cleared_at")
    private Instant clearedAt; // messages at or before this are hidden for this member

    @Column(name = "hidden")
    private Boolean hidden; // "deleted" from this member's chat list; null = visible

    public boolean isHiddenForMember() {
        return Boolean.TRUE.equals(hidden);
    }

    @Column(name = "pinned")
    private Boolean pinned; // pinned to the top of this member's chat list

    @Column(name = "archived")
    private Boolean archived; // moved to this member's "Archived" section

    public boolean isPinnedForMember() {
        return Boolean.TRUE.equals(pinned);
    }

    public boolean isArchivedForMember() {
        return Boolean.TRUE.equals(archived);
    }

    @PrePersist
    protected void onJoin() {
        this.joinedAt = Instant.now();
        this.lastReadAt = this.joinedAt; // don't count pre-join history as unread
    }
}