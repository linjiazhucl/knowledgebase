package com.chengming.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MilvusService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final ModelTestService modelTestService;
    private final String collection;
    private volatile boolean seedSynced;

    public MilvusService(JdbcTemplate jdbc, ObjectMapper mapper,
                         ModelTestService modelTestService,
                         @Value("${app.milvus.url}") String url,
                         @Value("${app.milvus.collection}") String collection) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(url).build();
        this.modelTestService = modelTestService;
        this.collection = collection;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAfterStartup() {
        syncSeededSegments();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void retrySeedSync() {
        if (!seedSynced) syncSeededSegments();
    }

    public synchronized boolean ensureCollection(int targetDimension) {
        if (targetDimension <= 0) return false;
        JsonNode description = describeCollection();
        if (isSuccessful(description)) {
            int existingDimension = collectionDimension(description);
            if (existingDimension == targetDimension) return ensureIndex();
            if (existingDimension <= 0 || !isSuccessful(post("/v2/vectordb/collections/drop", Map.of("collectionName", collection)))) {
                return false;
            }
            jdbc.update("UPDATE kb_segment SET vector_status = 'pending', model_config_id = NULL");
        }
        try {
            Map<String, Object> schema = new HashMap<>();
            schema.put("autoID", false);
            schema.put("enableDynamicField", false);
            schema.put("fields", List.of(
                    Map.of("fieldName", "segment_id", "dataType", "Int64", "isPrimary", true, "elementTypeParams", Map.of()),
                    Map.of("fieldName", "doc_id", "dataType", "Int64", "elementTypeParams", Map.of()),
                    Map.of("fieldName", "kb_id", "dataType", "Int64", "elementTypeParams", Map.of()),
                    Map.of("fieldName", "embedding", "dataType", "FloatVector", "elementTypeParams", Map.of("dim", String.valueOf(targetDimension)))
            ));
            JsonNode created = post("/v2/vectordb/collections/create", Map.of("collectionName", collection, "schema", schema));
            return isSuccessful(created) && ensureIndex();
        } catch (Exception createException) {
            return false;
        }
    }

    public boolean insertSegments(List<Map<String, Object>> segments) {
        if (segments == null || segments.isEmpty()) return true;
        EmbeddingConfig config = embeddingConfig();
        if (config == null) return false;
        List<Map<String, Object>> data = new ArrayList<>();
        try {
            int vectorDimension = 0;
            for (Map<String, Object> segment : segments) {
                long id = ((Number) segment.get("id")).longValue();
                long docId = ((Number) segment.get("doc_id")).longValue();
                long kbId = ((Number) segment.get("kb_id")).longValue();
                ModelTestService.EmbeddingResult embedding = modelTestService.embed(
                        config.endpoint(), config.model(), String.valueOf(segment.get("content")), config.apiKey());
                if (vectorDimension == 0) vectorDimension = embedding.dimension();
                if (embedding.dimension() != vectorDimension) return false;
                Map<String, Object> row = new HashMap<>();
                row.put("segment_id", id);
                row.put("doc_id", docId);
                row.put("kb_id", kbId);
                row.put("embedding", embedding.vector());
                data.add(row);
            }
            if (!ensureCollection(vectorDimension)) return false;
            JsonNode result = post("/v2/vectordb/entities/upsert", Map.of("collectionName", collection, "data", data));
            return isSuccessful(result) && ensureLoaded();
        } catch (Exception exception) {
            return false;
        }
    }

    public List<MilvusHit> search(String text, int topK) {
        return search(text, topK, null);
    }

    public List<MilvusHit> search(String text, int topK, Long kbId) {
        try {
            EmbeddingConfig config = embeddingConfig();
            if (config == null) return List.of();
            ModelTestService.EmbeddingResult embedding = modelTestService.embed(
                    config.endpoint(), config.model(), text, config.apiKey());
            if (!ensureCollection(embedding.dimension()) || !ensureLoaded()) return List.of();
            Map<String, Object> body = new HashMap<>();
            body.put("collectionName", collection);
            body.put("data", List.of(embedding.vector()));
            body.put("annsField", "embedding");
            body.put("limit", Math.max(1, topK));
            body.put("outputFields", List.of("segment_id", "doc_id", "kb_id"));
            body.put("params", Map.of());
            if (kbId != null) body.put("filter", "kb_id == " + kbId);
            JsonNode result = post("/v2/vectordb/entities/search", body);
            List<MilvusHit> hits = new ArrayList<>();
            JsonNode rows = result == null ? null : result.path("data");
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    if (row.isArray()) {
                        for (JsonNode item : row) hits.add(readHit(item));
                    } else if (row.isObject()) {
                        hits.add(readHit(row));
                    }
                }
            }
            return hits.stream().filter(hit -> hit.segmentId() > 0).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public boolean deleteByKbId(long kbId) {
        if (!hasCollection()) return true;
        try {
            return isSuccessful(post("/v2/vectordb/entities/delete", Map.of("collectionName", collection, "filter", "kb_id == " + kbId)));
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean deleteByDocumentId(long docId) {
        if (!hasCollection()) return true;
        try {
            return isSuccessful(post("/v2/vectordb/entities/delete", Map.of("collectionName", collection, "filter", "doc_id == " + docId)));
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isReady() {
        return hasCollection() && ensureLoaded();
    }

    private void syncSeededSegments() {
        if (seedSynced) return;
        if (embeddingConfig() == null) {
            jdbc.update("UPDATE kb_segment SET vector_status = 'pending', model_config_id = NULL WHERE vector_status = 'done'");
            dropCollection();
            seedSynced = true;
            return;
        }
        if (hasCollection()) {
            try {
                EmbeddingConfig config = embeddingConfig();
                ModelTestService.EmbeddingResult probe = modelTestService.embed(
                        config.endpoint(), config.model(), "你好", config.apiKey());
                if (!ensureCollection(probe.dimension()) || !ensureLoaded()) return;
                seedSynced = true;
                return;
            } catch (Exception ignored) {
                return;
            }
        }
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id, doc_id, kb_id, content FROM kb_segment WHERE vector_status = 'done'");
        if (segments.isEmpty() || insertSegments(segments)) seedSynced = true;
    }

    public synchronized void invalidateVectors() {
        jdbc.update("UPDATE kb_segment SET vector_status = 'pending', model_config_id = NULL");
        dropCollection();
        seedSynced = false;
    }

    private JsonNode post(String path, Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }

    private JsonNode describeCollection() {
        try {
            return post("/v2/vectordb/collections/describe", Map.of("collectionName", collection));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasCollection() {
        return isSuccessful(describeCollection());
    }

    private boolean dropCollection() {
        if (!hasCollection()) return true;
        try {
            return isSuccessful(post("/v2/vectordb/collections/drop", Map.of("collectionName", collection)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private int collectionDimension(JsonNode description) {
        JsonNode fields = description == null ? null : description.path("data").path("fields");
        if (fields == null || !fields.isArray()) return -1;
        for (JsonNode field : fields) {
            String fieldName = field.path("fieldName").asText(field.path("name").asText(""));
            if (!"embedding".equals(fieldName)) continue;
            String value = field.path("dim").asText("");
            value = dimensionFromParams(field.path("elementTypeParams"), value);
            value = dimensionFromParams(field.path("params"), value);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String dimensionFromParams(JsonNode params, String current) {
        if (!current.isBlank() || params == null || params.isMissingNode() || params.isNull()) return current;
        if (params.isObject()) {
            String value = params.path("dim").asText("");
            if (!value.isBlank()) return value;
            if ("dim".equals(params.path("key").asText(""))) return params.path("value").asText("");
        }
        if (params.isArray()) {
            for (JsonNode item : params) {
                if ("dim".equals(item.path("key").asText(""))) return item.path("value").asText("");
                String value = item.path("dim").asText("");
                if (!value.isBlank()) return value;
            }
        }
        return current;
    }

    private EmbeddingConfig embeddingConfig() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, api_url, model_name, api_key FROM kb_model_config WHERE model_type = 'embedding' AND status = 'active' LIMIT 1");
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.getFirst();
            String endpoint = Objects.toString(row.get("api_url"), "").trim();
            String model = Objects.toString(row.get("model_name"), "").trim();
            if (endpoint.isBlank() || model.isBlank()) return null;
            return new EmbeddingConfig(endpoint, model, Objects.toString(row.get("api_key"), ""),
                    ((Number) row.get("id")).longValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSuccessful(JsonNode response) {
        return response != null && response.path("code").asInt(-1) == 0;
    }

    private boolean ensureIndex() {
        try {
            JsonNode indexes = post("/v2/vectordb/indexes/list", Map.of("collectionName", collection));
            JsonNode names = indexes == null ? null : indexes.path("data");
            if (isSuccessful(indexes) && names.isArray()) {
                for (JsonNode name : names) {
                    if ("embedding_idx".equals(name.asText())) return true;
                }
            }
            JsonNode created = post("/v2/vectordb/indexes/create", Map.of(
                    "collectionName", collection,
                    "indexParams", List.of(Map.of(
                            "fieldName", "embedding",
                            "indexName", "embedding_idx",
                            "metricType", "COSINE",
                            "indexType", "AUTOINDEX",
                            "params", Map.of()))));
            return isSuccessful(created);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean ensureLoaded() {
        try {
            JsonNode state = getLoadState();
            if (isLoaded(state)) return true;
            JsonNode load = post("/v2/vectordb/collections/load", Map.of("collectionName", collection));
            if (!isSuccessful(load)) return false;
            for (int attempt = 0; attempt < 20; attempt++) {
                Thread.sleep(250);
                state = getLoadState();
                if (isLoaded(state)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private JsonNode getLoadState() {
        try {
            return post("/v2/vectordb/collections/get_load_state", Map.of("collectionName", collection));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isLoaded(JsonNode response) {
        return isSuccessful(response) && "LoadStateLoaded".equals(response.path("data").path("loadState").asText());
    }

    private MilvusHit readHit(JsonNode row) {
        long id = row.path("segment_id").asLong(row.path("id").asLong(0));
        double score = row.path("score").asDouble(row.path("distance").asDouble(0));
        return new MilvusHit(id, score);
    }

    private record EmbeddingConfig(String endpoint, String model, String apiKey, long id) {}
    public record MilvusHit(long segmentId, double score) {}
}
