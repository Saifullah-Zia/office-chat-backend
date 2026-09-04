package com.officechat.office_chat_backend.file;


import com.officechat.office_chat_backend.file.dto.AttachmentResponse;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
        import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentResponse upload(
            @AuthenticationPrincipal User currentUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") Long conversationId
    ) {
        Attachment attachment = fileService.upload(file, conversationId, currentUser);
        return AttachmentResponse.from(attachment);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long id
    ) {
        Attachment attachment = fileService.getById(id);
        Resource resource = fileService.download(id, currentUser);

        String contentType = attachment.getMimeType() != null
                ? attachment.getMimeType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        String disposition = contentType.startsWith("image/") ? "inline" : "attachment";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + attachment.getFileName() + "\"")
                .body(resource);
    }
}