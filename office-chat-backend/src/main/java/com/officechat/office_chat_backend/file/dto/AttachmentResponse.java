package com.officechat.office_chat_backend.file.dto;


import com.officechat.office_chat_backend.file.Attachment;

public record AttachmentResponse(
        Long id,
        String fileName,
        long fileSize,
        String mimeType,
        String downloadUrl
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getFileSize(),
                attachment.getMimeType(),
                com.officechat.office_chat_backend.file.DownloadUrlSigner.signedPath(attachment.getId())
        );
    }
}