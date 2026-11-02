package com.officechat.office_chat_backend.file;


import com.officechat.office_chat_backend.message.Message;
import com.officechat.office_chat_backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "attachments", indexes = @Index(name = "idx_attachments_message", columnList = "message_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Set at upload time, checked before the attachment can be linked to a message —
    // stops someone uploading into a conversation they're not part of.
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    // Nullable: file is uploaded first, then linked once the chat message is actually sent.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @Column(name = "file_name", nullable = false)
    private String fileName; // original name, shown to users

    @Column(name = "stored_file_name", nullable = false)
    private String storedFileName; // UUID-based name on disk

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "uploaded_at", updatable = false)
    private Instant uploadedAt;

    @PrePersist
    protected void onUpload() {
        this.uploadedAt = Instant.now();
    }
}