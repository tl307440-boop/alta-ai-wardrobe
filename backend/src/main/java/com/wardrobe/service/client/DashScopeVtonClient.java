package com.wardrobe.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeVtonClient {

    private final ObjectMapper objectMapper;

    @Value("${app.vton.dashscope-base-url:https://dashscope.aliyuncs.com}")
    private String dashscopeBaseUrl;

    @Value("${app.ai.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${app.vton.poll-interval-ms:2500}")
    private long pollIntervalMs;

    @Value("${app.vton.max-wait-ms:150000}")
    private long maxWaitMs;

    public record VtonResult(byte[] imageBytes, String remoteImageUrl, String taskId, String modelUsed) {}

    public VtonResult runVirtualTryOn(String apiKey,
                                      String model,
                                      String personImageUrl,
                                      String topGarmentUrl,
                                      String bottomGarmentUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置阿里云百炼 VTON API Key");
        }
        if (personImageUrl == null || personImageUrl.isBlank()) {
            throw new IllegalStateException("缺少模特全身参考图公网 URL");
        }
        boolean hasTop = topGarmentUrl != null && !topGarmentUrl.isBlank();
        boolean hasBottom = bottomGarmentUrl != null && !bottomGarmentUrl.isBlank();
        if (!hasTop && !hasBottom) {
            throw new IllegalStateException("上装与下装试穿图片至少需要提供一件");
        }

        String modelName = "aitryon".equalsIgnoreCase(model) ? "aitryon" : "aitryon-plus";
        String base = trimSlash(dashscopeBaseUrl);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("person_image_url", personImageUrl);
            if (hasTop) {
                input.put("top_garment_url", topGarmentUrl);
            }
            if (hasBottom) {
                input.put("bottom_garment_url", bottomGarmentUrl);
            }

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("resolution", -1);
            parameters.put("restore_face", true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("input", input);
            body.put("parameters", parameters);

            String submitUrl = base + "/api/v1/services/aigc/image2image/image-synthesis";
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest submitReq = HttpRequest.newBuilder(URI.create(submitUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("X-DashScope-Async", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> submitRes = client.send(submitReq, HttpResponse.BodyHandlers.ofString());
            if (submitRes.statusCode() < 200 || submitRes.statusCode() >= 300) {
                throw new IllegalStateException("百炼 VTON 提交失败 HTTP " + submitRes.statusCode()
                        + ": " + abbreviate(submitRes.body(), 240));
            }
            JsonNode submitJson = objectMapper.readTree(submitRes.body());
            String taskId = submitJson.path("output").path("task_id").asText("").trim();
            if (taskId.isBlank()) {
                String code = submitJson.path("code").asText("");
                String msg = submitJson.path("message").asText("未返回 task_id");
                throw new IllegalStateException("百炼 VTON 未返回任务 ID: " + code + " " + msg);
            }

            String taskUrl = base + "/api/v1/tasks/" + taskId;
            long deadline = System.currentTimeMillis() + Math.max(15000L, maxWaitMs);
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.max(500L, pollIntervalMs));
                HttpRequest pollReq = HttpRequest.newBuilder(URI.create(taskUrl))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + apiKey.trim())
                        .GET()
                        .build();
                HttpResponse<String> pollRes = client.send(pollReq, HttpResponse.BodyHandlers.ofString());
                if (pollRes.statusCode() < 200 || pollRes.statusCode() >= 300) {
                    throw new IllegalStateException("百炼 VTON 状态查询失败 HTTP " + pollRes.statusCode()
                            + ": " + abbreviate(pollRes.body(), 240));
                }
                JsonNode pollJson = objectMapper.readTree(pollRes.body());
                JsonNode output = pollJson.path("output");
                String status = output.path("task_status").asText("").toUpperCase();
                if ("SUCCEEDED".equals(status)) {
                    String imageUrl = output.path("image_url").asText("").trim();
                    if (imageUrl.isBlank() && output.path("results").isArray() && !output.path("results").isEmpty()) {
                        imageUrl = output.path("results").get(0).path("url").asText("").trim();
                    }
                    if (imageUrl.isBlank()) {
                        throw new IllegalStateException("百炼 VTON 任务完成但未返回 image_url");
                    }
                    byte[] imgBytes = downloadBytes(client, imageUrl);
                    return new VtonResult(imgBytes, imageUrl, taskId, modelName);
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                    String code = output.path("code").asText(pollJson.path("code").asText(""));
                    String msg = output.path("message").asText(pollJson.path("message").asText("任务执行失败"));
                    throw new IllegalStateException("百炼试衣任务失败：" + (code + " " + msg).trim());
                }
            }
            throw new IllegalStateException("百炼试衣轮询超时（超过 " + (maxWaitMs / 1000) + "s）");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百炼试衣等待被中断", ie);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            throw new IllegalStateException("百炼 VTON 调用异常：" + e.getMessage(), e);
        }
    }

    private byte[] downloadBytes(HttpClient client, String imageUrl) throws Exception {
        HttpRequest dlReq = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> dlRes = client.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
        if (dlRes.statusCode() < 200 || dlRes.statusCode() >= 300 || dlRes.body() == null || dlRes.body().length < 100) {
            throw new IllegalStateException("下载百炼 VTON 试衣结果图失败（HTTP " + dlRes.statusCode() + "）");
        }
        return dlRes.body();
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String r = url.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
