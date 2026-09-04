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

@Service
public class FileStorageService {

    @Value("${app.upload-dir}")
    private String uploadDir;

    // Block the extensions that pose a real risk in a chat/file-sharing tool
    private static final List<String> BLOCKED_EXTENSIONS =
            List.of("exe", "bat", "cmd", "sh", "msi", "com", "scr", "jar");

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

        String originalName = Path.of(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file")
                .getFileName().toString(); // strips any directory traversal attempt like ../../

        String extension = getExtension(originalName);
        if (BLOCKED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File type not allowed: ." + extension);
        }

        String storedFileName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
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

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex == -1 ? "" : fileName.substring(dotIndex + 1);
    }

    public record StoredFile(String originalName, String storedFileName, long size, String mimeType) {}
}