package com.officechat.office_chat_backend.conversation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

import java.time.Instant;


@Entity
@Table(name = "conversations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationType type;

    private String name;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "only_admins_can_post", nullable = false)
    @Builder.Default
    private boolean onlyAdminsCanPost = false;

    @Column(name = "is_announcement", nullable = false)
    @Builder.Default
    private boolean isAnnouncement = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Public channel: listed in the channel browser and joinable by anyone. */
    @Column(name = "is_public")
    private Boolean publicChannel;

    /** Secret code for "join via link"; null = links disabled. */
    @Column(name = "invite_code", unique = true, length = 40)
    private String inviteCode;

    /** Disappearing messages: messages older than this many days are removed. null/0 = keep forever. */
    @Column(name = "retention_days")
    private Integer retentionDays;

    /** Work-tracking labels shared by everyone in the chat ("Denial", "Pending payer"...), "|"-separated. */
    @Column(name = "labels", length = 500)
    private String labels;

    /** Mini-ticket status: null (not tracked), "OPEN" or "DONE". */
    @Column(name = "ticket_status", length = 10)
    private String ticketStatus;

    @Column(name = "ticket_updated_by")
    private Long ticketUpdatedBy;

    @Column(name = "ticket_updated_at")
    private Instant ticketUpdatedAt;

    public boolean isPublicChannel() {
        return Boolean.TRUE.equals(publicChannel);
    }

    @PrePersist
    protected  void oncreate(){
        this.createdAt =Instant.now();
    }
}
