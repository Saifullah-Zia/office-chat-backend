package com.officechat.office_chat_backend.file;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploaded files in a Cloudflare R2 bucket (S3-compatible). Switched on only when all four settings are present,
 * so local development and an unconfigured server keep using the upload folder.
 */
@Slf4j
@Component
public class R2Storage {

    @Value("${app.r2.endpoint:}")
    private String endpoint;
    @Value("${app.r2.access-key:}")
    private String accessKey;
    @Value("${app.r2.secret-key:}")
    private String secretKey;
    @Value("${app.r2.files-bucket:}")
    private String bucket;

    private S3Client client;

    @PostConstruct
    void init() {
        if (blank(endpoint) || blank(accessKey) || blank(secretKey) || blank(bucket)) {
            log.info("R2 file storage is off (R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY and R2_FILES_BUCKET are not all set)");
            return;
        }
        // A copied endpoint sometimes carries the bucket name after it; only the host is wanted.
        URI uri = URI.create(endpoint.trim());
        client = S3Client.builder()
                .endpointOverride(URI.create(uri.getScheme() + "://" + uri.getHost()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // R2 doesn't take every checksum header newer SDKs add by default.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        bucket = bucket.trim();
        log.info("Uploaded files are stored in R2 bucket '{}'", bucket);
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }

    public boolean enabled() {
        return client != null;
    }
    public void put(String key, InputStream in, long size, String contentType) {
        client.putObject(b -> b.bucket(bucket).key(key)
                        .contentType(blank(contentType) ? "application/octet-stream" : contentType),
                RequestBody.fromInputStream(in, size));
    }

    public void put(String key, Path file) throws IOException {
        String type = Files.probeContentType(file);
        try (InputStream in = Files.newInputStream(file)) {
            put(key, in, Files.size(file), type);
        }
    }

    /** The stored object, or null if the bucket doesn't have it. */
    public Resource get(String key) {
        try {
            HeadObjectResponse head = client.headObject(b -> b.bucket(bucket).key(key));
            return new R2Resource(key, head.contentLength(), head.lastModified() == null ? 0 : head.lastModified().toEpochMilli());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    /** A file into another bucket the same key may use (database backups). */
    public void putFile(String otherBucket, String key, Path file, String contentType) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(b -> b.bucket(otherBucket).key(key).contentType(contentType), RequestBody.fromInputStream(in, Files.size(file)));
        }
    }

    public boolean exists(String otherBucket, String key) {
        try {
            client.headObject(b -> b.bucket(otherBucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    public void delete(String key) {
        client.deleteObject(b -> b.bucket(bucket).key(key));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Streams the object when the response is written; Spring cuts out byte ranges itself (audio seeking). */
    private class R2Resource extends AbstractResource {
        private final String key;
        private final long length;
        private final long modified;

        R2Resource(String key, long length, long modified) {
            this.key = key;
            this.length = length;
            this.modified = modified;
        }

        @Override public String getDescription() { return "R2 object " + key; }
        @Override public String getFilename() { return key; }
        @Override public boolean exists() { return true; }
        @Override public long contentLength() { return length; }
        @Override public long lastModified() { return modified; }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                return client.getObject(b -> b.bucket(bucket).key(key));
            } catch (S3Exception e) {
                throw new IOException("Could not read " + key + " from R2", e);
            }
        }

        @Override public boolean equals(Object o) { return o instanceof R2Resource r && r.key.equals(key); }
        @Override public int hashCode() { return key.hashCode(); }
    }
}
