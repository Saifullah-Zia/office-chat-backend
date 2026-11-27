package com.officechat.office_chat_backend.file;

import com.officechat.office_chat_backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@lombok.extern.slf4j.Slf4j
@Service
@lombok.RequiredArgsConstructor
public class FileStorageService {

    private final R2Storage r2;

    @Value("${app.upload-dir}")
    private String uploadDir;

    // Block executables and scripts that pose a real risk in a chat/file-sharing tool
    private static final List<String> BLOCKED_EXTENSIONS = List.of(
            "exe", "bat", "cmd", "sh", "msi", "com", "scr", "jar", "ps1", "psm1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "cpl", "msc", "dll", "lnk", "reg", "pif", "application", "gadget", "msp",
            "apk", "app", "dmg", "pkg", "deb", "rpm", "iso", "html", "htm", "xhtml", "mht", "svgz"
    );

    private static final int MAX_NAME_LENGTH = 180;

    /** Last path segment only, no control characters, bounded length. */
    private static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "file";
        if (name.length() > MAX_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_NAME_LENGTH - ext.length()) + ext;
        }
        return name;
    }

    private Path resolveUploadPath() {
        Path path = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create upload directory");
        }
        return path;
    }

    public StoredFile store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot upload an empty file");
        }

        String originalName = sanitizeFileName(file.getOriginalFilename()); // strips ../ traversal etc.

        String extension = getExtension(originalName).toLowerCase();
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File type not allowed: ." + extension);
        }
        // Stored name uses only a safe, short extension; the original name is kept in the DB.
        String safeExt = extension.matches("[a-z0-9]{1,10}") ? extension : "";

        String storedFileName = UUID.randomUUID() + (safeExt.isEmpty() ? "" : "." + safeExt);
        if (r2.enabled()) {
            try (java.io.InputStream in = file.getInputStream()) {
                r2.put(storedFileName, in, file.getSize(), file.getContentType());
            } catch (Exception e) {
                log.error("Upload to R2 failed: {}", e.getMessage());
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file. Please try again.");
            }
            return new StoredFile(originalName, storedFileName, file.getSize(), file.getContentType());
        }

        Path target = resolveUploadPath().resolve(storedFileName);
        try {
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        return new StoredFile(originalName, storedFileName, file.getSize(), file.getContentType());
    }

    public Resource load(String storedFileName) {
        if (r2.enabled() && isSafeName(storedFileName)) {
            try {
                Resource remote = r2.get(storedFileName);
                if (remote != null) return remote;
            } catch (Exception e) {
                log.warn("R2 read failed for {}: {}", storedFileName, e.getMessage());
            }
            // Not copied up yet: fall back to the upload folder below.
        }
        try {
            Path base = resolveUploadPath();
            Path filePath = base.resolve(storedFileName).normalize();
            if (!filePath.startsWith(base)) throw new ApiException(HttpStatus.NOT_FOUND, "File not found on disk");
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "File not found on disk");
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid file path");
        }
    }

    /** Removes a stored file from disk; missing files are ignored. */
    public void delete(String storedFileName) {
        if (r2.enabled() && isSafeName(storedFileName)) {
            try {
                r2.delete(storedFileName);
            } catch (Exception e) {
                log.warn("Could not delete {} from R2: {}", storedFileName, e.getMessage());
            }
        }
        try {
            Path base = resolveUploadPath();
            Path target = base.resolve(storedFileName).normalize();
            if (target.startsWith(base)) Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}: {}", storedFileName, e.getMessage());
        }
    }

    /** Stored names are ours (uuid.ext); anything else never reaches the bucket. */
    private static boolean isSafeName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{1,200}") && !name.startsWith(".");
    }

    /**
     * Once R2 is switched on, copies files still in the upload folder up to the bucket, in the background so the
     * app starts at once. Files stay on disk too (they are served from there until copied), so nothing is lost
     * if this is interrupted: the next start carries on.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void copyLocalFilesToR2() {
        if (!r2.enabled()) return;
        Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return;
        Thread t = new Thread(() -> {
            int copied = 0, failed = 0;
            try (var files = Files.list(base)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String name = p.getFileName().toString();
                    if (!Files.isRegularFile(p) || !isSafeName(name)) continue;
                    try {
                        if (!r2.has(name)) {
                            r2.put(name, p);
                            copied++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.warn("Could not copy {} to R2: {}", name, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("Could not list the upload folder: {}", e.getMessage());
            }
            log.info("R2 copy finished: {} file(s) copied, {} failed", copied, failed);
        }, "r2-copy");
        t.setDaemon(true);
        t.start();
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex == -1 ? "" : fileName.substring(dotIndex + 1);
    }

    public record StoredFile(String originalName, String storedFileName, long size, String mimeType) {}
}