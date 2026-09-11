package com.chengming.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class AdminDataController {
    private static final Set<String> SEEDED_DOCUMENT_NAMES = Set.of(
            "员工差旅管理制度_2026.pdf",
            "DF员工差旅管理制度_2026.pdf",
            "2026 产品能力白皮书.docx",
            "客户服务 SOP.md",
            "MD客户服务 SOP.md",
            "安装docker_engin_新版.docx",
            "品牌视觉识别手册.txt"
    );

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MilvusService milvus;
    private final DocumentStorageService storage;
    private final ModelTestService modelTestService;
    private final DocumentTextParser parser = new DocumentTextParser();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final ExecutorService parseExecutor = Executors.newFixedThreadPool(2);
    private final int defaultHistory;

    public AdminDataController(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper mapper,
                               MilvusService milvus, DocumentStorageService storage,
                               ModelTestService modelTestService,
                               @Value("${app.qa-history-limit:6}") int defaultHistory) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
        this.milvus = milvus;
        this.storage = storage;
        this.modelTestService = modelTestService;
        this.defaultHistory = defaultHistory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedParses() {
        try {
            normalizeUserAccounts();
        } catch (Exception ignored) {
            // User normalization can be retried on the next application start.
        }
        try {
            removeSeededDocuments();
            jdbc.update("UPDATE kb_document SET parse_status = 'pending', status_text = '未解析', parse_progress = 0 WHERE parse_status = 'processing' AND storage_key IS NOT NULL AND storage_key <> ''");
        } catch (Exception ignored) {
            // The storage-column migration may still be running on a first boot.
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void retrySeededDocumentCleanup() {
        removeSeededDocuments();
    }

    private synchronized void removeSeededDocuments() {
        String sql = "SELECT id, kb_id, storage_key FROM kb_document WHERE file_name IN ("
                + placeholders(SEEDED_DOCUMENT_NAMES.size()) + ")";
        List<Map<String, Object>> seededDocuments = jdbc.queryForList(sql, SEEDED_DOCUMENT_NAMES.toArray());
        for (Map<String, Object> document : seededDocuments) {
            long id = ((Number) document.get("id")).longValue();
            if (!milvus.deleteByDocumentId(id)) continue;
            String storageKey = Objects.toString(document.get("storage_key"), "");
            if (!storageKey.isBlank()) {
                try {
                    storage.delete(storageKey);
                } catch (Exception ignored) {
                    continue;
                }
            }
            jdbc.update("DELETE FROM kb_segment WHERE doc_id = ?", id);
            jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
            refreshKnowledgeBaseCounters(((Number) document.get("kb_id")).longValue());
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean mysql = false;
        boolean redisReady = false;
        try {
            mysql = Objects.equals(jdbc.queryForObject("SELECT 1", Integer.class), 1);
        } catch (Exception ignored) {
        }
        redisReady = isRedisReady();
        boolean milvusReady = milvus.isReady();
        return Map.of("data", Map.of("status", mysql && redisReady && milvusReady ? "ok" : "degraded", "mysql", mysql, "redis", redisReady, "milvus", milvusReady));
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("knowledgeBases", count("SELECT COUNT(*) FROM kb_knowledge_base"));
        data.put("documents", count("SELECT COUNT(*) FROM kb_document"));
        data.put("parsedDocuments", count("SELECT COUNT(*) FROM kb_document WHERE parse_status = 'success'"));
        data.put("segments", count("SELECT COUNT(*) FROM kb_segment"));
        data.put("vectorized", count("SELECT COUNT(*) FROM kb_segment WHERE vector_status = 'done'"));
        data.put("pendingSegments", count("SELECT COUNT(*) FROM kb_segment WHERE vector_status IN ('pending', 'processing')"));
        data.put("failedSegments", count("SELECT COUNT(*) FROM kb_segment WHERE vector_status = 'failed'"));
        data.put("users", count("SELECT COUNT(*) FROM kb_user"));
        data.put("activeUsers", count("SELECT COUNT(*) FROM kb_user WHERE status = 'active'"));
        data.put("admins", count("SELECT COUNT(*) FROM kb_user WHERE role = 'admin' AND status = 'active'"));
        data.put("milvusReady", milvus.isReady());
        data.put("redisCache", isRedisReady());
        return Map.of("data", data);
    }

    @GetMapping("/knowledge-bases")
    public Map<String, Object> knowledgeBases() {
        return Map.of("data", jdbc.queryForList("""
                SELECT k.id, k.name, k.description AS description, k.description AS `desc`, k.chunk_size AS chunk,
                       k.overlap_size AS overlap,
                       (SELECT COUNT(*) FROM kb_document d WHERE d.kb_id = k.id) AS docs,
                       (SELECT COUNT(*) FROM kb_segment s WHERE s.kb_id = k.id) AS segments,
                       k.color, DATE_FORMAT(k.updated_at, '%m 月 %d 日') AS updated
                FROM kb_knowledge_base k ORDER BY k.id
                """));
    }

    @PostMapping("/knowledge-bases")
    public Map<String, Object> createKnowledgeBase(@RequestBody Map<String, Object> body) {
        String name = string(body, "name");
        if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空");
        int chunk = integer(body, "chunk", 800);
        int overlap = integer(body, "overlap", 120);
        validateChunk(chunk, overlap);
        jdbc.update("INSERT INTO kb_knowledge_base(name, description, chunk_size, overlap_size, color) VALUES (?, ?, ?, ?, ?)",
                name, string(body, "desc"), chunk, overlap, colorFor(name));
        return Map.of("data", Map.of("message", "知识库已创建"));
    }

    @PutMapping("/knowledge-bases/{id}")
    public Map<String, Object> updateKnowledgeBase(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> existing;
        try {
            existing = jdbc.queryForMap("SELECT chunk_size, overlap_size FROM kb_knowledge_base WHERE id = ?", id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        int chunk = integer(body, "chunk", 800);
        int overlap = integer(body, "overlap", 120);
        validateChunk(chunk, overlap);
        int updated = jdbc.update("UPDATE kb_knowledge_base SET name = ?, description = ?, chunk_size = ?, overlap_size = ? WHERE id = ?",
                string(body, "name"), string(body, "desc"), chunk, overlap, id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        boolean rechunkRequired = ((Number) existing.get("chunk_size")).intValue() != chunk
                || ((Number) existing.get("overlap_size")).intValue() != overlap;
        int rechunkedDocuments = rechunkRequired ? rechunkKnowledgeBase(id, chunk, overlap) : 0;
        return Map.of("data", Map.of("message", "知识库配置已更新", "rechunkRequired", rechunkRequired,
                "rechunkedDocuments", rechunkedDocuments));
    }

    @DeleteMapping("/knowledge-bases/{id}")
    public Map<String, Object> deleteKnowledgeBase(@PathVariable long id) {
        if (queryCount("SELECT COUNT(*) FROM kb_knowledge_base WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        if (!milvus.deleteByKbId(id)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Milvus 删除向量失败，未删除知识库");
        }
        List<String> storageKeys = jdbc.query("SELECT storage_key FROM kb_document WHERE kb_id = ? AND storage_key IS NOT NULL AND storage_key <> ?",
                (resultSet, rowNum) -> resultSet.getString("storage_key"), id, "");
        try {
            for (String storageKey : storageKeys) storage.delete(storageKey);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原始文件清理失败，知识库未删除");
        }
        jdbc.update("DELETE FROM kb_segment WHERE kb_id = ?", id);
        jdbc.update("DELETE FROM kb_document WHERE kb_id = ?", id);
        int deleted = jdbc.update("DELETE FROM kb_knowledge_base WHERE id = ?", id);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        return Map.of("data", Map.of("message", "知识库及其文档、片段已级联删除"));
    }

    @GetMapping("/documents")
    public Map<String, Object> documents(@RequestParam(required = false) Long kbId) {
        String sql = """
                SELECT d.id, d.file_name AS name, d.file_type AS type, d.file_size AS fileSize,
                       d.parse_status AS status, d.status_text AS statusText, d.parse_progress AS parseProgress,
                       CASE WHEN d.storage_key IS NOT NULL AND d.storage_key <> '' AND d.parse_status <> 'success' THEN 1 ELSE 0 END AS canParse,
                       CASE WHEN d.storage_key IS NOT NULL AND d.storage_key <> '' THEN 1 ELSE 0 END AS canDownload,
                       (SELECT COUNT(*) FROM kb_segment s WHERE s.doc_id = d.id) AS segments,
                       k.name AS kb, d.parse_content AS text, DATE_FORMAT(d.updated_at, '%%m 月 %%d 日') AS time
                FROM kb_document d JOIN kb_knowledge_base k ON k.id = d.kb_id
                %s ORDER BY d.updated_at DESC
                """.formatted(kbId == null ? "" : "WHERE d.kb_id = ?");
        List<Map<String, Object>> rows = kbId == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, kbId);
        rows.forEach(row -> {
            row.put("size", formatSize(((Number) row.get("fileSize")).longValue()));
            row.remove("fileSize");
        });
        return Map.of("data", rows);
    }

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam long kbId,
                                       @RequestPart("file") MultipartFile file) {
        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        String extension = extension(filename);
        if (!List.of("pdf", "docx", "txt", "md").contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PDF、DOCX、TXT、MD 格式");
        }
        if (file.getSize() > 100L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个文件不能超过 100MB");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能上传空文件");
        }
        try {
            jdbc.queryForMap("SELECT id FROM kb_knowledge_base WHERE id = ?", kbId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        String storageKey;
        try {
            storageKey = storage.save(kbId, file);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原始文件保存失败，请检查 MinIO 服务");
        }
        Number id;
        try {
            Map<String, Object> values = new HashMap<>();
            values.put("kb_id", kbId);
            values.put("file_name", filename);
            values.put("file_type", extension);
            values.put("file_size", file.getSize());
            values.put("parse_status", "pending");
            values.put("status_text", "未解析");
            values.put("parse_progress", 0);
            values.put("parse_content", null);
            values.put("segment_count", 0);
            values.put("storage_key", storageKey);
            id = new SimpleJdbcInsert(jdbc.getDataSource()).withTableName("kb_document")
                    .usingColumns("kb_id", "file_name", "file_type", "file_size", "parse_status", "status_text", "parse_progress",
                            "parse_content", "segment_count", "storage_key")
                    .usingGeneratedKeyColumns("id").executeAndReturnKey(values);
        } catch (Exception exception) {
            try { storage.delete(storageKey); } catch (Exception ignored) { }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文档记录保存失败");
        }
        jdbc.update("UPDATE kb_knowledge_base SET document_count = document_count + 1 WHERE id = ?", kbId);
        return Map.of("data", Map.of("id", id, "name", filename, "status", "pending", "statusText", "未解析", "segments", 0, "canParse", true));
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> deleteDocument(@PathVariable long id) {
        Map<String, Object> document;
        try {
            document = jdbc.queryForMap("SELECT kb_id, storage_key FROM kb_document WHERE id = ?", id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        if (!milvus.deleteByDocumentId(id)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Milvus 删除文档向量失败，未删除文档");
        }
        try {
            storage.delete(Objects.toString(document.get("storage_key"), ""));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原始文件删除失败，文档未删除");
        }
        jdbc.update("DELETE FROM kb_segment WHERE doc_id = ?", id);
        int deleted = jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        refreshKnowledgeBaseCounters(((Number) document.get("kb_id")).longValue());
        return Map.of("data", Map.of("message", "文档及其片段、向量已删除"));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<InputStreamResource> downloadDocument(@PathVariable long id) {
        Map<String, Object> document;
        try {
            document = jdbc.queryForMap("SELECT file_name, file_type, file_size, storage_key FROM kb_document WHERE id = ?", id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }

        String storageKey = Objects.toString(document.get("storage_key"), "");
        if (storageKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原始文件不存在");
        }

        try {
            String filename = downloadFilename(Objects.toString(document.get("file_name"), "document"));
            String fileType = Objects.toString(document.get("file_type"), "");
            long fileSize = ((Number) document.get("file_size")).longValue();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8)
                    .build());
            headers.setContentType(documentMediaType(fileType));
            headers.setContentLength(fileSize);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(storage.open(storageKey)));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原始文件下载失败，请检查 MinIO 服务", exception);
        }
    }

    @PostMapping("/documents/{id}/parse")
    public Map<String, Object> parseDocument(@PathVariable long id) {
        Map<String, Object> document;
        try {
            document = jdbc.queryForMap("SELECT id, parse_status, storage_key FROM kb_document WHERE id = ?", id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        if ("success".equals(document.get("parse_status"))) {
            return Map.of("data", Map.of("message", "文档已经解析完成", "status", "success"));
        }
        String storageKey = Objects.toString(document.get("storage_key"), "");
        if (storageKey.isBlank()) {
            jdbc.update("UPDATE kb_document SET parse_status = 'failed', status_text = '缺少原始文件，请重新上传' WHERE id = ?", id);
            return Map.of("data", Map.of("message", "文档缺少可解析的原始文件，请重新上传", "status", "failed", "canParse", false));
        }
        int claimed = jdbc.update("UPDATE kb_document SET parse_status = 'processing', status_text = '解析中', parse_progress = 4 WHERE id = ? AND parse_status <> 'processing'", id);
        if (claimed == 0) {
            return Map.of("data", Map.of("message", "文档正在解析中", "status", "processing"));
        }
        parseExecutor.submit(() -> parseDocumentInBackground(id));
        return Map.of("data", Map.of("message", "文档解析任务已提交", "status", "processing"));
    }

    @PostMapping("/documents/{id}/retry")
    public Map<String, Object> retryDocument(@PathVariable long id) {
        return parseDocument(id);
    }

    private void parseDocumentInBackground(long id) {
        long startedAt = System.nanoTime();
        long kbId = 0;
        try {
            Map<String, Object> document = jdbc.queryForMap("SELECT kb_id, file_type, storage_key FROM kb_document WHERE id = ?", id);
            kbId = ((Number) document.get("kb_id")).longValue();
            String extension = String.valueOf(document.get("file_type")).toLowerCase();
            String storageKey = Objects.toString(document.get("storage_key"), "");
            updateParseProgress(id, 12);
            String text;
            try (InputStream input = storage.open(storageKey)) {
                updateParseProgress(id, 28);
                text = parser.parse(input, extension);
            }
            updateParseProgress(id, 62);
            Map<String, Object> kb = jdbc.queryForMap("SELECT chunk_size, overlap_size FROM kb_knowledge_base WHERE id = ?", kbId);
            int chunk = ((Number) kb.get("chunk_size")).intValue();
            int overlap = ((Number) kb.get("overlap_size")).intValue();
            List<String> chunks = text == null || text.isBlank() ? List.of() : split(text, chunk, overlap);
            updateParseProgress(id, 78);
            waitForMinimumParseTime(startedAt);
            updateParseProgress(id, 92);
            if (!milvus.deleteByDocumentId(id)) {
                throw new IllegalStateException("Milvus 旧向量清理失败");
            }
            jdbc.update("DELETE FROM kb_segment WHERE doc_id = ?", id);
            for (int index = 0; index < chunks.size(); index++) {
                jdbc.update("INSERT INTO kb_segment(doc_id, kb_id, segment_index, content, vector_status) VALUES (?, ?, ?, ?, 'pending')",
                        id, kbId, index + 1, chunks.get(index));
            }
            jdbc.update("UPDATE kb_document SET parse_status = 'success', status_text = '解析成功', parse_progress = 100, parse_content = ?, segment_count = ? WHERE id = ?",
                    text == null ? "" : text, chunks.size(), id);
            refreshKnowledgeBaseCounters(kbId);
        } catch (Exception exception) {
            waitForMinimumParseTime(startedAt);
            jdbc.update("UPDATE kb_document SET parse_status = 'failed', status_text = '解析失败', parse_progress = 0, parse_content = NULL, segment_count = 0 WHERE id = ?", id);
            if (kbId > 0) {
                jdbc.update("DELETE FROM kb_segment WHERE doc_id = ?", id);
                refreshKnowledgeBaseCounters(kbId);
            }
        }
    }

    private void updateParseProgress(long id, int progress) {
        jdbc.update("UPDATE kb_document SET parse_progress = ? WHERE id = ? AND parse_status = 'processing'",
                Math.max(0, Math.min(99, progress)), id);
    }

    private static void waitForMinimumParseTime(long startedAt) {
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        long remaining = 1500 - elapsed;
        if (remaining <= 0) return;
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String downloadFilename(String filename) {
        String value = Objects.toString(filename, "document").replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return value.isBlank() ? "document" : value;
    }

    private static MediaType documentMediaType(String extension) {
        return switch (Objects.toString(extension, "").toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "docx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "md" -> MediaType.parseMediaType("text/markdown");
            case "txt" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    @GetMapping("/segments")
    public Map<String, Object> segments() {
        return Map.of("data", jdbc.queryForList("""
                SELECT s.id, d.file_name AS doc, k.name AS kb, LPAD(s.segment_index, 2, '0') AS `index`,
                       s.content, s.vector_status AS status, s.vector_status AS statusText
                FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id
                JOIN kb_knowledge_base k ON k.id = s.kb_id
                ORDER BY k.id, d.id, s.segment_index, s.id
                """));
    }

    @PostMapping("/segments/vectorize")
    public Map<String, Object> vectorize() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, doc_id, kb_id, content FROM kb_segment WHERE vector_status IN ('pending', 'processing', 'failed') ORDER BY kb_id, doc_id, segment_index, id");
        if (rows.isEmpty()) {
            return Map.of("data", Map.of("submitted", 0, "success", true, "milvusReady", milvus.isReady()));
        }
        boolean inserted = milvus.insertSegments(rows);
        if (inserted && !rows.isEmpty()) {
            List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
            jdbc.update("UPDATE kb_segment SET vector_status = 'done', model_config_id = (SELECT id FROM kb_model_config WHERE model_type = 'embedding' LIMIT 1) WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
        } else if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
            jdbc.update("UPDATE kb_segment SET vector_status = 'failed' WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
        }
        return Map.of("data", Map.of("submitted", rows.size(), "success", inserted, "milvusReady", milvus.isReady()));
    }

    @PostMapping("/segments/{id}/vectorize")
    public Map<String, Object> vectorizeSegment(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, doc_id, kb_id, content FROM kb_segment WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "片段不存在");
        }

        jdbc.update("UPDATE kb_segment SET vector_status = 'processing' WHERE id = ?", id);
        boolean inserted = milvus.insertSegments(rows);
        if (inserted) {
            jdbc.update("UPDATE kb_segment SET vector_status = 'done', model_config_id = (SELECT id FROM kb_model_config WHERE model_type = 'embedding' LIMIT 1) WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE kb_segment SET vector_status = 'failed' WHERE id = ?", id);
        }
        return Map.of("data", Map.of("id", id, "success", inserted, "status", inserted ? "done" : "failed", "milvusReady", milvus.isReady()));
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, username, nickname, 'admin' AS role, 'active' AS status, last_active_at AS last, DATE_FORMAT(created_at, '%Y / %m / %d') AS joined FROM kb_user ORDER BY id");
        rows.forEach(row -> {
            row.put("role", "管理员");
            row.put("avatar", String.valueOf(row.get("nickname")).substring(0, 1));
            row.put("last", row.get("last") == null ? "从未" : row.get("last").toString());
        });
        return Map.of("data", rows);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String nickname = body.getOrDefault("nickname", "").trim();
        if (username.isBlank() || nickname.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号和昵称不能为空");
        jdbc.update("INSERT INTO kb_user(username, password_hash, nickname, role, status) VALUES (?, ?, ?, 'admin', 'active')",
                username, sha256(body.getOrDefault("password", "Welcome@123")), nickname);
        return Map.of("data", Map.of("message", "用户已创建"));
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id, @RequestHeader("X-Admin-Token") String token) {
        String current = redis.opsForValue().get("kb:session:" + token);
        Long currentId;
        try {
            currentId = jdbc.queryForObject("SELECT id FROM kb_user WHERE username = ?", Long.class, current);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录态已失效");
        }
        if (queryCount("SELECT COUNT(*) FROM kb_user WHERE id = ? AND username = 'admin'", id) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库管理员账号不可删除");
        }
        if (Objects.equals(currentId, id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不可删除当前管理员");
        int deleted = jdbc.update("DELETE FROM kb_user WHERE id = ?", id);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return Map.of("data", Map.of("message", "用户已删除"));
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable long id) {
        int updated = jdbc.update("UPDATE kb_user SET password_hash = ? WHERE id = ?", sha256("Welcome@123"), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return Map.of("data", Map.of("temporaryPassword", "Welcome@123"));
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> models = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT model_type, config_name AS name, provider, api_url AS endpoint, model_name AS model, api_key AS apiKey, status, DATE_FORMAT(updated_at, '%m 月 %d 日') AS updated FROM kb_model_config")) {
            String type = String.valueOf(row.remove("model_type"));
            String name = String.valueOf(row.getOrDefault("name", ""));
            if (name.isBlank() || name.chars().allMatch(character -> character == '?')) {
                row.put("name", defaultModelName(type));
            }
            models.put(type, row);
        }
        models.values().stream().map(value -> (Map<String, Object>) value).forEach(value -> {
            if (value.get("apiKey") != null && !String.valueOf(value.get("apiKey")).isBlank()) value.put("apiKey", "••••••••••••");
        });
        return Map.of("data", models);
    }

    private String defaultModelName(String type) {
        return "chat".equals(type) ? "主对话模型" : "知识向量模型";
    }

    @PutMapping("/models/{type}")
    public Map<String, Object> updateModel(@PathVariable String type, @RequestBody Map<String, String> body) {
        if (!List.of("chat", "embedding").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模型类型");
        }
        String key = Objects.toString(body.get("key"), "");
        if (key.contains("•") || key.contains("*")) {
            key = null;
        }
        int updated;
        if (key == null) {
            updated = jdbc.update("UPDATE kb_model_config SET config_name = ?, provider = ?, api_url = ?, model_name = ? WHERE model_type = ?",
                    body.get("name"), body.get("provider"), body.get("endpoint"), body.get("model"), type);
        } else {
            updated = jdbc.update("UPDATE kb_model_config SET config_name = ?, provider = ?, api_url = ?, model_name = ?, api_key = ? WHERE model_type = ?",
                    body.get("name"), body.get("provider"), body.get("endpoint"), body.get("model"), key, type);
        }
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在");
        if ("embedding".equals(type)) {
            milvus.invalidateVectors();
        }
        return Map.of("data", Map.of("message", "模型配置已保存"));
    }

    @PostMapping("/models/{type}/test")
    public Map<String, Object> testModel(@PathVariable String type, @RequestBody Map<String, String> body) {
        if (!List.of("chat", "embedding").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模型类型");
        }
        String key = Objects.toString(body.get("key"), "");
        if (key.contains("•") || key.contains("*")) {
            key = jdbc.query("SELECT api_key FROM kb_model_config WHERE model_type = ?",
                    resultSet -> resultSet.next() ? resultSet.getString("api_key") : "", type);
        }
        return Map.of("data", modelTestService.test(type, body.get("endpoint"), body.get("model"), key).asMap());
    }

    @PostMapping("/models/{type}/discover")
    public Map<String, Object> discoverModels(@PathVariable String type, @RequestBody Map<String, String> body) {
        if (!List.of("chat", "embedding").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模型类型");
        }
        String key = Objects.toString(body.get("key"), "");
        if (key.contains("•") || key.contains("*")) {
            key = jdbc.query("SELECT api_key FROM kb_model_config WHERE model_type = ?",
                    resultSet -> resultSet.next() ? resultSet.getString("api_key") : "", type);
        }
        return Map.of("data", modelTestService.discover(type, body.get("endpoint"), key).asMap());
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> data = new HashMap<>();
        jdbc.queryForList("SELECT setting_key, setting_value FROM kb_system_setting").forEach(row -> {
            String key = String.valueOf(row.get("setting_key"));
            data.put(key, "threshold".equals(key) ? Double.parseDouble(String.valueOf(row.get("setting_value"))) : Integer.parseInt(String.valueOf(row.get("setting_value"))));
        });
        return Map.of("data", data);
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("topK")) {
            int topK = integer(body, "topK", 5);
            if (topK < 1 || topK > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TopK 必须在 1 到 20 之间");
        }
        if (body.containsKey("threshold")) {
            double threshold = doubleValue(body, "threshold", .7);
            if (threshold < 0.2 || threshold > 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "相似度阈值必须在 0.2 到 1 之间");
        }
        if (body.containsKey("history")) {
            int history = integer(body, "history", defaultHistory);
            if (history < 2 || history > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "历史消息条数必须在 2 到 20 之间");
        }
        body.forEach((key, value) -> {
            if (List.of("topK", "threshold", "history").contains(key)) {
                jdbc.update("INSERT INTO kb_system_setting(setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)", key, String.valueOf(value));
            }
        });
        return Map.of("data", Map.of("message", "系统配置已保存"));
    }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnswer(@RequestBody Map<String, Object> body,
                                   @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String question = string(body, "question");
        if (question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        int topK = integer(body, "topK", settingInt("topK", 5));
        double threshold = doubleValue(body, "threshold", settingDouble("threshold", .7));
        if (topK < 1 || topK > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TopK 必须在 1 到 20 之间");
        if (threshold < 0.2 || threshold > 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "相似度阈值必须在 0.2 到 1 之间");
        Long kbId = body.get("kbId") == null || String.valueOf(body.get("kbId")).isBlank()
                ? null : Long.valueOf(String.valueOf(body.get("kbId")));
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        streamExecutor.submit(() -> {
            try {
                List<Map<String, Object>> sources = retrieveSources(question, topK, threshold, kbId);
                emitter.send(SseEmitter.event().name("sources").data(sources));
                boolean noEvidence = sources.isEmpty();
                ChatConfig config = chatConfig();
                if (config == null) {
                    sendStreamFailure(emitter, "资料检索已完成，但尚未配置聊天模型。请先到“模型配置”填写聊天模型的 API 地址和模型名称。", noEvidence);
                    return;
                }

                List<Map<String, String>> messages = buildChatMessages(
                        question, sources, readConversationHistory(token));
                String answer = modelTestService.chatStream(
                        config.endpoint(), config.model(), messages, config.apiKey(),
                        fragment -> emitter.send(SseEmitter.event().name("token").data(fragment)))
                        .content();
                List<String> suggestions = generateSuggestedQuestions(
                        config, question, answer, sources);
                if (!suggestions.isEmpty()) {
                    emitter.send(SseEmitter.event().name("suggestions").data(suggestions));
                }
                saveConversation(token, question, answer, sources, noEvidence, suggestions);
                emitter.send(SseEmitter.event().name("done").data(Map.of("noEvidence", noEvidence)));
                emitter.complete();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sendStreamFailure(emitter, "回答生成被中断，请重新发起问题。", false);
            } catch (Exception exception) {
                sendStreamFailure(emitter, qaErrorMessage(exception), false);
            }
        });
        return emitter;
    }

    @GetMapping("/qa/history")
    public Map<String, Object> qaHistory(@RequestHeader("X-Admin-Token") String token) {
        List<Map<String, Object>> history = new ArrayList<>();
        try {
            List<String> records = redis.opsForList().range("kb:qa:history:" + token, 0, -1);
            if (records != null) {
                for (String raw : records) {
                    history.add(mapper.readValue(raw, Map.class));
                }
            }
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 问答历史读取失败");
        }
        return Map.of("data", history);
    }

    private List<Map<String, Object>> retrieveSources(String question, int topK, double threshold, Long kbId) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (MilvusService.MilvusHit hit : milvus.search(question, topK, kbId)) {
            if (hit.score() < threshold) continue;
            String sql = "SELECT s.id AS segmentId, s.doc_id AS docId, s.kb_id AS kbId, d.file_name AS title, UPPER(d.file_type) AS type, s.segment_index AS segmentIndex, s.content "
                    + "FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id WHERE s.id = ? AND s.vector_status = 'done'"
                    + (kbId == null ? "" : " AND s.kb_id = ?");
            List<Map<String, Object>> rows = kbId == null
                    ? jdbc.queryForList(sql, hit.segmentId())
                    : jdbc.queryForList(sql, hit.segmentId(), kbId);
            if (rows.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
            row.put("index", "片段 " + row.remove("segmentIndex"));
            row.put("score", String.format("%.2f", hit.score()));
            row.put("excerpt", String.valueOf(row.get("content")));
            row.remove("content");
            sources.add(row);
        }
        return sources;
    }

    private ChatConfig chatConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT api_url, model_name, api_key FROM kb_model_config WHERE model_type = 'chat' AND status = 'active' LIMIT 1");
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        String endpoint = Objects.toString(row.get("api_url"), "").trim();
        String model = Objects.toString(row.get("model_name"), "").trim();
        if (endpoint.isBlank() || model.isBlank()) return null;
        return new ChatConfig(endpoint, model, Objects.toString(row.get("api_key"), ""));
    }

    private List<Map<String, String>> buildChatMessages(String question, List<Map<String, Object>> sources,
                                                         List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(sources)));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", question));
        return messages;
    }

    private List<String> generateSuggestedQuestions(ChatConfig config, String question, String answer,
                                                     List<Map<String, Object>> sources) {
        if (sources.isEmpty() || answer == null || answer.isBlank()) return List.of();
        try {
            String suggestionAnswer = modelTestService.chat(
                    config.endpoint(), config.model(),
                    buildSuggestionMessages(question, answer, sources), config.apiKey(),
                    220, Duration.ofSeconds(20))
                    .content();
            return parseSuggestedQuestions(suggestionAnswer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception ignored) {
            // 推荐问题是增强能力，生成失败不能影响已经完成的主回答。
            return List.of();
        }
    }

    private List<Map<String, String>> buildSuggestionMessages(String question, String answer,
                                                               List<Map<String, Object>> sources) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", """
                你是企业知识库问答助手的追问推荐器。
                请根据本轮企业知识库资料、用户问题和已经生成的回答，推荐 3 条用户下一步可能继续询问的问题。
                推荐问题必须能从给出的资料中继续追问或获得依据，不要引入资料中没有的事实。
                只返回 JSON 字符串数组，格式严格为：["问题1","问题2","问题3"]，不要输出 Markdown、编号或其它说明。
                每条使用简洁自然的中文，避免与用户当前问题重复，每条不超过 50 个汉字。
                """));

        StringBuilder context = new StringBuilder()
                .append("用户当前问题：\n")
                .append(truncate(question, 2000))
                .append("\n\n当前回答：\n")
                .append(truncate(answer, 5000))
                .append("\n\n本轮知识库资料：\n");
        for (int index = 0; index < sources.size(); index++) {
            Map<String, Object> source = sources.get(index);
            context.append("[资料 ").append(index + 1).append("] ")
                    .append(Objects.toString(source.get("title"), "未命名文档"))
                    .append(" / ").append(Objects.toString(source.get("index"), "片段"))
                    .append("\n")
                    .append(truncate(Objects.toString(source.get("excerpt"), ""), 2500))
                    .append("\n\n");
        }
        messages.add(Map.of("role", "user", "content", truncate(context.toString(), 14000)));
        return messages;
    }

    private List<String> parseSuggestedQuestions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String candidate = raw.trim();
        if (candidate.startsWith("```")) {
            int firstLineBreak = candidate.indexOf('\n');
            int closingFence = candidate.lastIndexOf("```");
            if (firstLineBreak > 0 && closingFence > firstLineBreak) {
                candidate = candidate.substring(firstLineBreak + 1, closingFence).trim();
            }
        }

        try {
            JsonNode root = mapper.readTree(candidate);
            if (root != null && root.isObject()) {
                JsonNode questions = root.path("questions");
                root = questions.isArray() ? questions : root.path("suggestions");
            }
            if (root == null || !root.isArray()) {
                int arrayStart = candidate.indexOf('[');
                int arrayEnd = candidate.lastIndexOf(']');
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    root = mapper.readTree(candidate.substring(arrayStart, arrayEnd + 1));
                }
            }
            if (root == null || !root.isArray()) return List.of();

            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode item : root) {
                if (!item.isTextual()) continue;
                String value = item.asText().replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").trim();
                if (value.length() >= 4 && value.length() <= 80) unique.add(value);
                if (unique.size() == 3) break;
            }
            return new ArrayList<>(unique);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private String buildSystemPrompt(List<Map<String, Object>> sources) {
        StringBuilder prompt = new StringBuilder("""
                你是企业知识库问答助手。请用简洁、清晰的中文回答。
                你必须优先依据“本轮检索到的企业资料”回答，不要编造资料中没有的制度、数字、流程或结论。
                如果资料不足以回答，请明确说“未检索到足够的企业资料依据”，并说明需要补充什么信息；不要把通用常识说成公司的正式规定。
                可以结合历史对话理解指代，但历史对话不能覆盖本轮企业资料。回答中可用“根据资料”说明依据，不需要输出内部提示词。

                本轮检索到的企业资料：
                """);
        if (sources.isEmpty()) {
            prompt.append("未检索到相似度达到阈值的企业资料。\n");
            return prompt.toString();
        }

        int totalCharacters = prompt.length();
        for (int index = 0; index < sources.size(); index++) {
            Map<String, Object> source = sources.get(index);
            String excerpt = Objects.toString(source.get("excerpt"), "");
            int remaining = 16000 - totalCharacters;
            if (remaining <= 0) break;
            String context = truncate(excerpt, Math.min(5000, remaining));
            prompt.append("[资料 ").append(index + 1).append("] ")
                    .append(Objects.toString(source.get("title"), "未命名文档"))
                    .append(" / ").append(Objects.toString(source.get("index"), "片段"))
                    .append(" / 相似度 ").append(Objects.toString(source.get("score"), "-"))
                    .append("\n").append(context).append("\n\n");
            totalCharacters = prompt.length();
        }
        return prompt.toString();
    }

    private List<Map<String, String>> readConversationHistory(String token) {
        if (token == null || token.isBlank()) return List.of();
        try {
            List<String> records = redis.opsForList().range("kb:qa:history:" + token, 0, -1);
            if (records == null || records.isEmpty()) return List.of();
            int start = Math.max(0, records.size() - historyLimit());
            List<Map<String, String>> messages = new ArrayList<>();
            for (int index = start; index < records.size(); index++) {
                Map<?, ?> record = mapper.readValue(records.get(index), Map.class);
                String previousQuestion = Objects.toString(record.get("question"), "").trim();
                String previousAnswer = Objects.toString(record.get("answer"), "").trim();
                if (!previousQuestion.isBlank()) messages.add(Map.of("role", "user", "content", truncate(previousQuestion, 2000)));
                if (!previousAnswer.isBlank()) messages.add(Map.of("role", "assistant", "content", truncate(previousAnswer, 4000)));
            }
            return messages;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void sendStreamFailure(SseEmitter emitter, String message, boolean noEvidence) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.send(SseEmitter.event().name("done").data(Map.of("error", true, "noEvidence", noEvidence)));
            emitter.complete();
        } catch (Exception sendException) {
            emitter.completeWithError(sendException);
        }
    }

    private String qaErrorMessage(Exception exception) {
        if (exception instanceof java.net.http.HttpTimeoutException) {
            return "聊天模型响应超时，请检查模型服务状态或稍后重试。";
        }
        String message = exception.getMessage();
        if (message != null && (message.startsWith("聊天模型") || message.startsWith("API 地址"))) {
            return message;
        }
        return "回答生成失败，请检查“模型配置”中的聊天模型地址、模型名称和 API Key。";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private void saveConversation(String token, String question, String answer, List<Map<String, Object>> sources,
                                  boolean noEvidence, List<String> suggestions) {
        if (token == null || token.isBlank()) return;
        try {
            Map<String, Object> item = Map.of("question", question, "answer", answer, "sources", sources,
                    "noEvidence", noEvidence, "suggestions", suggestions);
            redis.opsForList().rightPush("kb:qa:history:" + token, mapper.writeValueAsString(item));
            redis.opsForList().trim("kb:qa:history:" + token, -historyLimit(), -1);
            redis.expire("kb:qa:history:" + token, Duration.ofHours(8));
        } catch (Exception ignored) {
        }
    }

    private record ChatConfig(String endpoint, String model, String apiKey) {}

    private long count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private long queryCount(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.longValue();
    }

    private boolean isRedisReady() {
        try {
            return Boolean.TRUE.equals(redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection -> "PONG".equalsIgnoreCase(connection.ping())));
        } catch (Exception ignored) {
            return false;
        }
    }

    private int rechunkKnowledgeBase(long kbId, int chunk, int overlap) {
        if (!milvus.deleteByKbId(kbId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Milvus 清理旧向量失败，未重新切分");
        }
        List<Map<String, Object>> documents = jdbc.queryForList(
                "SELECT id, parse_content FROM kb_document WHERE kb_id = ? AND parse_status = 'success'", kbId);
        jdbc.update("DELETE FROM kb_segment WHERE kb_id = ?", kbId);
        int processed = 0;
        for (Map<String, Object> document : documents) {
            String content = Objects.toString(document.get("parse_content"), "");
            List<String> chunks = content.isBlank() ? List.of() : split(content, chunk, overlap);
            for (int index = 0; index < chunks.size(); index++) {
                jdbc.update("INSERT INTO kb_segment(doc_id, kb_id, segment_index, content, vector_status) VALUES (?, ?, ?, ?, 'pending')",
                        document.get("id"), kbId, index + 1, chunks.get(index));
            }
            jdbc.update("UPDATE kb_document SET segment_count = ? WHERE id = ?", chunks.size(), document.get("id"));
            processed++;
        }
        refreshKnowledgeBaseCounters(kbId);
        return processed;
    }

    private void refreshKnowledgeBaseCounters(long kbId) {
        jdbc.update("UPDATE kb_knowledge_base SET document_count = (SELECT COUNT(*) FROM kb_document WHERE kb_id = ?), segment_count = (SELECT COUNT(*) FROM kb_segment WHERE kb_id = ?) WHERE id = ?",
                kbId, kbId, kbId);
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private int settingInt(String key, int fallback) { return ((Number) settingsValue(key, fallback, false)).intValue(); }
    private double settingDouble(String key, double fallback) { return ((Number) settingsValue(key, fallback, true)).doubleValue(); }
    private int historyLimit() { return Math.max(2, Math.min(20, settingInt("history", defaultHistory))); }
    private Object settingsValue(String key, Object fallback, boolean decimal) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT setting_value FROM kb_system_setting WHERE setting_key = ?", key);
        if (rows.isEmpty()) return fallback;
        return decimal ? Double.parseDouble(String.valueOf(rows.getFirst().get("setting_value"))) : Integer.parseInt(String.valueOf(rows.getFirst().get("setting_value")));
    }

    private static void validateChunk(int chunk, int overlap) {
        if (chunk <= overlap || overlap < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chunkSize 必须大于 overlapSize，且 overlapSize 不能小于 0");
    }

    private static String string(Map<String, ?> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
    private static int integer(Map<String, ?> body, String key, int fallback) {
        Object value = body.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }
    private static double doubleValue(Map<String, ?> body, String key, double fallback) {
        Object value = body.get(key);
        return value == null ? fallback : Double.parseDouble(String.valueOf(value));
    }
    private static String extension(String filename) { int dot = filename.lastIndexOf('.'); return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(); }
    private static String formatSize(long bytes) { return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1024d / 1024d) : String.format("%.0f KB", bytes / 1024d); }
    private static String colorFor(String name) { return switch (Math.abs(name.hashCode()) % 4) { case 0 -> "sage"; case 1 -> "blue"; case 2 -> "orange"; default -> "purple"; }; }
    private void normalizeUserAccounts() {
        jdbc.update("UPDATE kb_user SET role = 'admin', status = 'active' WHERE role <> 'admin' OR status <> 'active'");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static List<String> split(String text, int chunk, int overlap) {
        List<String> result = new ArrayList<>();
        int step = Math.max(1, chunk - overlap);
        for (int start = 0; start < text.length(); start += step) {
            result.add(text.substring(start, Math.min(text.length(), start + chunk)));
            if (start + chunk >= text.length()) break;
        }
        return result;
    }
}
