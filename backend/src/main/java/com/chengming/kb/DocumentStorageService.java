package com.chengming.kb;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class DocumentStorageService {
    private final JdbcTemplate jdbc;
    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public DocumentStorageService(JdbcTemplate jdbc,
                                  @Value("${app.storage.endpoint}") String endpoint,
                                  @Value("${app.storage.access-key}") String accessKey,
                                  @Value("${app.storage.secret-key}") String secretKey,
                                  @Value("${app.storage.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        ensureStorageColumn();
        try {
            ensureBucket();
        } catch (Exception ignored) {
            // MinIO may still be starting; the first upload will retry bucket setup.
        }
    }

    public String save(long kbId, MultipartFile file) throws Exception {
        ensureBucket();
        String filename = safeFilename(file.getOriginalFilename());
        String key = "documents/" + kbId + "/" + UUID.randomUUID() + "-" + filename;
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        try (InputStream input = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        }
        return key;
    }

    public InputStream open(String key) throws Exception {
        ensureBucket();
        return client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
    }

    public void delete(String key) throws Exception {
        if (key == null || key.isBlank()) return;
        ensureBucket();
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) return;
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        bucketReady = true;
    }

    private void ensureStorageColumn() {
        if (jdbc.queryForList("SHOW COLUMNS FROM kb_document LIKE 'storage_key'").isEmpty()) {
            jdbc.execute("ALTER TABLE kb_document ADD COLUMN storage_key VARCHAR(512) NULL AFTER segment_count");
        }
        if (jdbc.queryForList("SHOW COLUMNS FROM kb_document LIKE 'parse_progress'").isEmpty()) {
            jdbc.execute("ALTER TABLE kb_document ADD COLUMN parse_progress INT NOT NULL DEFAULT 0 AFTER status_text");
        }
    }

    private String safeFilename(String original) {
        String value = original == null ? "document" : original.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return value.isBlank() ? "document" : value;
    }
}
