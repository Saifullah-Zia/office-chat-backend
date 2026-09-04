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
    @PrePersist
    protected  void oncreate(){
        this.createdAt =Instant.now();
    }
}
