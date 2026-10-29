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

    // Types that are safe to render in the browser. Everything else (incl. SVG and HTML, which
    // can carry script) is forced to download.
    private static final java.util.Set<String> INLINE_TYPES = java.util.Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/avif",
            "application/pdf"
    );

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long id,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig
    ) {
        Attachment attachment = fileService.getById(id);
        Resource resource = fileService.download(id, currentUser, exp, sig);

        MediaType mediaType;
        try {
            mediaType = attachment.getMimeType() != null
                    ? MediaType.parseMediaType(attachment.getMimeType())
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String baseType = mediaType.getType() + "/" + mediaType.getSubtype();
        boolean inline = INLINE_TYPES.contains(baseType)
                || baseType.startsWith("audio/") || baseType.startsWith("video/");

        org.springframework.http.ContentDisposition disposition =
                (inline ? org.springframework.http.ContentDisposition.inline()
                        : org.springframework.http.ContentDisposition.attachment())
                        .filename(attachment.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                        .build();

        var response = ResponseEntity.ok()
                .contentType(inline ? mediaType : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff");
        // Sandbox anything opened directly in a tab. (Chrome's PDF viewer refuses to run sandboxed.)
        if (!"application/pdf".equals(baseType)) {
            response = response.header("Content-Security-Policy", "default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'; sandbox");
        }
        return response.body(resource);
    }
}