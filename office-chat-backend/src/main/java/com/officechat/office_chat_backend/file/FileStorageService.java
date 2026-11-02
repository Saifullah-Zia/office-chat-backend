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
public class FileStorageService {

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
        Path target = resolveUploadPath().resolve(storedFileName);

        try {
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        return new StoredFile(originalName, storedFileName, file.getSize(), file.getContentType());
    }

    public Resource load(String storedFileName) {
        try {
            Path filePath = resolveUploadPath().resolve(storedFileName).normalize();
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
        try {
            Path base = resolveUploadPath();
            Path target = base.resolve(storedFileName).normalize();
            if (target.startsWith(base)) Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}: {}", storedFileName, e.getMessage());
        }
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex == -1 ? "" : fileName.substring(dotIndex + 1);
    }

    public record StoredFile(String originalName, String storedFileName, long size, String mimeType) {}
}