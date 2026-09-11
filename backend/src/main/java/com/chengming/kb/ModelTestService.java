package com.chengming.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelTestService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CHAT_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final String TEST_INPUT = "你好";

    private final ObjectMapper mapper;
    private final HttpClient client;

    public ModelTestService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public TestResult test(String type, String endpoint, String model, String apiKey) {
        long startedAt = System.nanoTime();
        try {
            String requestUrl = requestUrl(type, endpoint);
            if (model == null || model.isBlank()) {
                return failure("failed", "模型名称不能为空", elapsedMs(startedAt), 0);
            }

            Map<String, Object> payload = new HashMap<>();
            if ("chat".equals(type)) {
                payload.put("model", model.trim());
                payload.put("messages", List.of(Map.of("role", "user", "content", TEST_INPUT)));
                payload.put("max_tokens", 1);
                payload.put("stream", false);
            } else {
                payload.put("model", model.trim());
                payload.put("input", TEST_INPUT);
            }

            HttpResponse<String> response = sendRequest(requestUrl, payload, apiKey);
            long latencyMs = elapsedMs(startedAt);
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                if (isValidModelResponse(type, response.body())) {
                    return new TestResult(true, "success", "接口响应正常", latencyMs, statusCode, response.body());
                }
                return failure("failed", "接口返回内容不是有效的" + ("chat".equals(type) ? "聊天" : "向量") + "模型响应", latencyMs, statusCode, response.body());
            }
            if (statusCode == 408 || statusCode == 504) {
                return failure("timeout", "接口响应超时（HTTP " + statusCode + "）", latencyMs, statusCode, response.body());
            }
            if (statusCode == 502) {
                return failure("failed", "接口返回 HTTP 502，连接失败", latencyMs, statusCode, response.body());
            }
            return failure("failed", "接口返回 HTTP " + statusCode, latencyMs, statusCode, response.body());
        } catch (HttpTimeoutException exception) {
            return failure("timeout", "接口请求超时，请检查地址或服务状态", elapsedMs(startedAt), 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("failed", "接口测试被中断", elapsedMs(startedAt), 0);
        } catch (IllegalArgumentException exception) {
            return failure("failed", exception.getMessage() == null ? "API 地址无效" : exception.getMessage(), elapsedMs(startedAt), 0);
        } catch (IOException | RuntimeException exception) {
            return failure("failed", "接口无法连接，请检查地址、端口和模型服务", elapsedMs(startedAt), 0);
        }
    }

    public EmbeddingResult embed(String endpoint, String model, String input, String apiKey)
            throws IOException, InterruptedException {
        String requestUrl = requestUrl("embedding", endpoint);
        if (model == null || model.isBlank()) {
            throw new IOException("向量模型名称不能为空");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model.trim());
        payload.put("input", input == null ? "" : input);
        HttpResponse<String> response = sendRequest(requestUrl, payload, apiKey);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("向量接口返回 HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root == null ? null : root.get("data");
        JsonNode embedding = data != null && data.isArray() && !data.isEmpty()
                ? data.get(0).get("embedding") : null;
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new IOException("向量接口未返回有效 embedding 数组");
        }

        List<Float> vector = new ArrayList<>(embedding.size());
        for (JsonNode value : embedding) {
            if (!value.isNumber()) throw new IOException("embedding 数组包含非数字值");
            vector.add(value.floatValue());
        }
        return new EmbeddingResult(vector, vector.size());
    }

    public ChatResult chat(String endpoint, String model, List<Map<String, String>> messages, String apiKey)
            throws IOException, InterruptedException {
        return chat(endpoint, model, messages, apiKey, 1200, CHAT_REQUEST_TIMEOUT);
    }

    public ChatResult chat(String endpoint, String model, List<Map<String, String>> messages, String apiKey,
                           int maxTokens, Duration timeout)
            throws IOException, InterruptedException {
        String requestUrl = requestUrl("chat", endpoint);
        if (model == null || model.isBlank()) {
            throw new IOException("聊天模型名称不能为空");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IOException("聊天消息不能为空");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model.trim());
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);

        HttpResponse<String> response = sendRequest(requestUrl, payload, apiKey, timeout);
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("聊天模型接口返回 HTTP " + statusCode + "，请检查模型配置");
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root == null ? null : root.get("choices");
        JsonNode content = choices != null && choices.isArray() && !choices.isEmpty()
                ? choices.get(0).path("message").path("content") : null;
        String answer = chatContent(content);
        if (answer.isBlank()) {
            throw new IOException("聊天模型未返回有效回答");
        }
        return new ChatResult(answer.trim(), statusCode);
    }

    /**
     * Calls an OpenAI-compatible chat endpoint in streaming mode and forwards
     * each content fragment as soon as it arrives from the model service.
     */
    public ChatResult chatStream(String endpoint, String model, List<Map<String, String>> messages, String apiKey,
                                 ChatTokenConsumer onToken)
            throws IOException, InterruptedException {
        String requestUrl = requestUrl("chat", endpoint);
        if (model == null || model.isBlank()) {
            throw new IOException("聊天模型名称不能为空");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IOException("聊天消息不能为空");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model.trim());
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 1200);
        payload.put("stream", true);

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(CHAT_REQUEST_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int statusCode = response.statusCode();
        try (InputStream body = response.body()) {
            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("聊天模型接口返回 HTTP " + statusCode + "，请检查模型配置"
                        + (errorBody.isBlank() ? "" : "：" + truncate(errorBody, 240)));
            }

            StringBuilder answer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).stripLeading();
                        if (!data.isBlank()) appendStreamEvent(new StringBuilder(data), answer, onToken);
                    }
                }
            }

            if (answer.isEmpty() || answer.toString().isBlank()) {
                throw new IOException("聊天模型未返回有效回答");
            }
            return new ChatResult(answer.toString().trim(), statusCode);
        }
    }

    private void appendStreamEvent(StringBuilder eventData, StringBuilder answer, ChatTokenConsumer onToken)
            throws IOException {
        if (eventData.isEmpty()) return;
        String data = eventData.toString().trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return;

        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (IOException exception) {
            throw new IOException("聊天模型返回了无效的流式数据", exception);
        }
        if (root == null) return;

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        String fragment = chatContent(delta.path("content"));
        if (fragment.isEmpty()) fragment = chatContent(choice.path("text"));
        if (fragment.isEmpty()) fragment = chatContent(choice.path("message").path("content"));
        if (fragment.isEmpty()) return;

        answer.append(fragment);
        if (onToken != null) onToken.accept(fragment);
    }

    public ModelListResult discover(String type, String endpoint, String apiKey) {
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = sendModelsRequest(modelsUrl(endpoint), apiKey);
            long latencyMs = elapsedMs(startedAt);
            int statusCode = response.statusCode();
            if (statusCode == 408 || statusCode == 504) {
                return modelListFailure("timeout", "模型列表请求超时，请检查地址或服务状态", latencyMs, statusCode);
            }
            if (statusCode < 200 || statusCode >= 300) {
                return modelListFailure("failed", "模型列表接口返回 HTTP " + statusCode, latencyMs, statusCode);
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()) {
                return modelListFailure("failed", "接口返回格式无效，未找到模型列表", latencyMs, statusCode);
            }

            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isBlank() && !models.contains(id)) models.add(id);
            }
            if (models.isEmpty()) {
                return modelListFailure("failed", "接口未返回可用模型", latencyMs, statusCode);
            }
            return new ModelListResult(true, "success", "已获取 " + models.size() + " 个模型", latencyMs, statusCode, models);
        } catch (HttpTimeoutException exception) {
            return modelListFailure("timeout", "模型列表请求超时，请检查地址或服务状态", elapsedMs(startedAt), 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return modelListFailure("failed", "模型列表请求被中断", elapsedMs(startedAt), 0);
        } catch (IllegalArgumentException exception) {
            return modelListFailure("failed", exception.getMessage() == null ? "API 地址无效" : exception.getMessage(), elapsedMs(startedAt), 0);
        } catch (IOException | RuntimeException exception) {
            return modelListFailure("failed", "模型列表无法获取，请检查地址、端口和模型服务", elapsedMs(startedAt), 0);
        }
    }

    private HttpResponse<String> sendRequest(String requestUrl, Map<String, Object> payload, String apiKey)
            throws IOException, InterruptedException {
        return sendRequest(requestUrl, payload, apiKey, REQUEST_TIMEOUT);
    }

    private HttpResponse<String> sendRequest(String requestUrl, Map<String, Object> payload, String apiKey,
                                             Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String chatContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonNode part : content) {
            if (part.isTextual()) {
                result.append(part.asText());
            } else if (part.isObject() && part.path("type").asText("").equals("text")) {
                result.append(part.path("text").asText(""));
            }
        }
        return result.toString();
    }

    private HttpResponse<String> sendModelsRequest(String requestUrl, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean isValidModelResponse(String type, String body) {
        try {
            if (body == null || body.isBlank()) return false;
            JsonNode root = mapper.readTree(body);
            if (root == null) return false;
            if ("chat".equals(type)) {
                JsonNode choices = root.get("choices");
                return choices != null && choices.isArray() && !choices.isEmpty();
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) return false;
            JsonNode embedding = data.get(0).get("embedding");
            return embedding != null && embedding.isArray() && !embedding.isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    private String requestUrl(String type, String endpoint) {
        String base = normalizedEndpoint(endpoint);
        String suffix = "chat".equals(type) ? "/chat/completions" : "/embeddings";
        return base.endsWith(suffix) ? base : base + suffix;
    }

    private String modelsUrl(String endpoint) {
        String base = normalizedEndpoint(endpoint);
        for (String suffix : List.of("/chat/completions", "/embeddings")) {
            if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length());
        }
        return base.endsWith("/models") ? base : base + "/models";
    }

    private String normalizedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("API 地址不能为空");
        String base = endpoint.trim();
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API 地址格式无效");
        }
        if (!List.of("http", "https").contains(baseUri.getScheme())) {
            throw new IllegalArgumentException("API 地址必须以 http:// 或 https:// 开头");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private TestResult failure(String status, String message, long latencyMs, int httpStatus) {
        return failure(status, message, latencyMs, httpStatus, "");
    }

    private TestResult failure(String status, String message, long latencyMs, int httpStatus, String responseBody) {
        return new TestResult(false, status, message, latencyMs, httpStatus, responseBody == null ? "" : responseBody);
    }

    private ModelListResult modelListFailure(String status, String message, long latencyMs, int httpStatus) {
        return new ModelListResult(false, status, message, latencyMs, httpStatus, List.of());
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    public record TestResult(boolean success, String status, String message, long latencyMs, int httpStatus,
                             String responseBody) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("status", status);
            result.put("message", message);
            result.put("latencyMs", latencyMs);
            result.put("httpStatus", httpStatus);
            result.put("responseBody", responseBody);
            return result;
        }
    }

    public record ModelListResult(boolean success, String status, String message, long latencyMs, int httpStatus,
                                  List<String> models) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("status", status);
            result.put("message", message);
            result.put("latencyMs", latencyMs);
            result.put("httpStatus", httpStatus);
            result.put("models", models);
            return result;
        }
    }

    public record EmbeddingResult(List<Float> vector, int dimension) {}

    public record ChatResult(String content, int httpStatus) {}

    @FunctionalInterface
    public interface ChatTokenConsumer {
        void accept(String token) throws IOException;
    }
}
