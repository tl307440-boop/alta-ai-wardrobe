package com.wardrobe.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alibaba Cloud Bailian (DashScope) active multi-image virtual try-on & outfit fusion client.
 * Uses /api/v1/services/aigc/multimodal-generation/generation with qwen-image-2.1-pro/turbo,
 * qwen-image-3.0-pro, or wan2.7-image-pro and Base64 white-backdrop JPEGs.
 */
@Slf4j
@Service
public class DashScopeVtonClient {

    public static final String DEFAULT_MODEL = "qwen-image-2.1-pro";

    private final ObjectMapper objectMapper;

    public DashScopeVtonClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Value("${app.vton.dashscope-base-url:https://dashscope.aliyuncs.com}")
    private String dashscopeBaseUrl;

    @Value("${app.ai.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${app.vton.poll-interval-ms:2500}")
    private long pollIntervalMs;

    @Value("${app.vton.max-wait-ms:150000}")
    private long maxWaitMs;

    public record VtonResult(byte[] imageBytes, String remoteImageUrl, String taskId, String modelUsed) {}

    public record GarmentImageInput(String slotLabel, String name, String imageDataOrUrl) {}

    /**
     * Normalizes configured VTON model names and automatically migrates retired aitryon* values.
     */
    public static String normalizeModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODEL;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "qwen-image-2.1-pro", "qwen-image-2.1-turbo", "qwen-image-3.0-pro", "wan2.7-image-pro" -> trimmed;
            default -> DEFAULT_MODEL;
        };
    }

    public static int maxGarmentsForModel(String model) {
        String normalized = normalizeModel(model);
        return "qwen-image-3.0-pro".equals(normalized) ? 2 : 5;
    }

    public VtonResult runMultiImageTryOn(String apiKey,
                                          String model,
                                          String personImageDataOrUrl,
                                          List<GarmentImageInput> garments,
                                          String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("百炼 VTON API Key 不能为空");
        }
        if (personImageDataOrUrl == null || personImageDataOrUrl.isBlank()) {
            throw new IllegalArgumentException("缺少模特参考图");
        }
        if (garments == null || garments.isEmpty()) {
            throw new IllegalArgumentException("至少需要一件服装参考图才能调用多图试穿");
        }

        String modelName = normalizeModel(model);
        int maxGarments = maxGarmentsForModel(modelName);
        List<GarmentImageInput> usableGarments = garments.stream()
                .filter(g -> g != null && g.imageDataOrUrl() != null && !g.imageDataOrUrl().isBlank())
                .limit(maxGarments)
                .toList();
        if (usableGarments.isEmpty()) {
            throw new IllegalArgumentException("缺少有效的服装图片数据");
        }

        String base = trimSlash(dashscopeBaseUrl);
        String endpoint = base + "/api/v1/services/aigc/multimodal-generation/generation";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(2000L, connectTimeoutMs)))
                .build();

        try {
            List<Map<String, Object>> contentList = new ArrayList<>();
            contentList.add(Map.of("image", personImageDataOrUrl.trim()));
            for (GarmentImageInput g : usableGarments) {
                contentList.add(Map.of("image", g.imageDataOrUrl().trim()));
            }
            contentList.add(Map.of("text", prompt == null ? "" : prompt.trim()));

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", contentList);

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("n", 1);
            parameters.put("size", "960*1280");
            parameters.put("watermark", false);
            if (modelName.startsWith("qwen-image-")) {
                // qwen-image-2.1-* rejects negative_prompt; keep prompt verbatim via prompt_extend=false
                parameters.put("prompt_extend", false);
            } else if (modelName.startsWith("wan2.7-")) {
                parameters.put("enable_sequential", false);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelName);
            payload.put("input", Map.of("messages", List.of(message)));
            payload.put("parameters", parameters);

            String body = objectMapper.writeValueAsString(payload);
            long requestTimeoutMs = Math.max(30_000L, Math.min(maxWaitMs, 180_000L));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("百炼多图试穿调用失败 HTTP " + resp.statusCode() + ": " + abbreviate(resp.body(), 240));
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String errCode = root.path("code").asText("");
            if (!errCode.isBlank()) {
                throw new IllegalStateException("百炼返回错误 [" + errCode + "]: " + root.path("message").asText(""));
            }

            String requestId = root.path("request_id").asText("");
            String remoteImageUrl = extractImageUrl(root);
            if (remoteImageUrl.isBlank()) {
                throw new IllegalStateException("百炼响应未包含成图 URL: " + abbreviate(resp.body(), 240));
            }

            byte[] bytes = downloadOrDecodeImage(client, remoteImageUrl);
            log.info("DashScope multi-image VTON completed: model={}, requestId={}, garments={}, bytes={}",
                    modelName, requestId, usableGarments.size(), bytes.length);
            return new VtonResult(bytes, remoteImageUrl, requestId, modelName);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("阿里云百炼 VTON 调用异常: " + ex.getMessage(), ex);
        }
    }

    private String extractImageUrl(JsonNode root) {
        JsonNode choices = root.path("output").path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String img = item.path("image").asText("");
                    if (!img.isBlank()) {
                        return img.trim();
                    }
                }
            }
        }
        JsonNode results = root.path("output").path("results");
        if (results.isArray() && !results.isEmpty()) {
            String url = results.get(0).path("url").asText("");
            if (!url.isBlank()) {
                return url.trim();
            }
        }
        return "";
    }

    private byte[] downloadOrDecodeImage(HttpClient client, String urlOrDataUri) throws Exception {
        if (urlOrDataUri.startsWith("data:")) {
            int comma = urlOrDataUri.indexOf(',');
            if (comma <= 0) {
                throw new IllegalStateException("百炼返回的 data URI 格式无效");
            }
            return Base64.getDecoder().decode(urlOrDataUri.substring(comma + 1));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(urlOrDataUri))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null || resp.body().length < 64) {
            throw new IllegalStateException("下载百炼试穿成图失败 HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String trimSlash(String value) {
        if (value == null) return "";
        String s = value.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String abbreviate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
