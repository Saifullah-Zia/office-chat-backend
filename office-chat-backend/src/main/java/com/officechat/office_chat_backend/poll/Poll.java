package com.officechat.office_chat_backend.poll;

import com.officechat.office_chat_backend.message.Message;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "polls")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(nullable = false)
    private String question;

    @Column(name = "multiple_answers", nullable = false)
    private boolean multipleAnswers;

    // Voting stops after this (null = open until closed by hand)
    @Column(name = "closes_at")
    private Instant closesAt;

    // Set when the creator or an admin closes the poll early
    @Column(name = "closed_at")
    private Instant closedAt;

    public boolean isClosed() {
        return closedAt != null || (closesAt != null && Instant.now().isAfter(closesAt));
    }

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
