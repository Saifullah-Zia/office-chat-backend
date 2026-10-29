package com.officechat.office_chat_backend.file;



import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.conversation.ConversationService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileStorageService storageService;
    private final AttachmentRepository attachmentRepository;
    private final ConversationService conversationService;
    private final DownloadUrlSigner urlSigner;
    private final com.officechat.office_chat_backend.common.RateLimiter rateLimiter;

    public Attachment upload(MultipartFile file, Long conversationId, User uploader) {
        if (uploader == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Login required");
        }
        rateLimiter.check("upload:" + uploader.getId(), 30, java.time.Duration.ofMinutes(1),
                "You're uploading too fast. Please wait a moment.");
        conversationService.assertMember(conversationId, uploader.getId());

        FileStorageService.StoredFile stored = storageService.store(file);

        Attachment attachment = Attachment.builder()
                .conversationId(conversationId)
                .uploadedBy(uploader)
                .fileName(stored.originalName())
                .storedFileName(stored.storedFileName())
                .fileSize(stored.size())
                .mimeType(stored.mimeType())
                .build();

        return attachmentRepository.save(attachment);
    }

    /**
     * Access is granted to a logged-in member of the attachment's conversation, or to anyone
     * presenting a valid, unexpired signature from {@link DownloadUrlSigner}.
     */
    public Resource download(Long attachmentId, User requester, Long exp, String sig) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "File not found"));

        if (requester != null) {
            conversationService.assertMember(attachment.getConversationId(), requester.getId());
        } else if (!urlSigner.isValid(attachmentId, exp, sig)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This download link is invalid or has expired");
        }

        return storageService.load(attachment.getStoredFileName());
    }

    public Attachment getById(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
    }
}