package com.wardrobe.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class AiConfigDto {

    @Data
    public static class SaveRequest {
        private String providerName = "dashscope";

        @NotBlank(message = "API Base URL 不能为空")
        private String baseUrl;

        /** Blank keeps existing encrypted API Key; non-blank replaces it */
        private String apiKey;

        /** Explicit flag to clear stored API Key */
        private Boolean clearApiKey = false;

        @NotBlank(message = "视觉识别模型名不能为空")
        private String visionModel;

        @NotBlank(message = "搭配推理模型名不能为空")
        private String chatModel;

        /** Optional OpenAI-compatible image generation / try-on model name */
        private String imageModel = "gpt-image-1";

        private Boolean enabled = true;

        /** Dedicated Alibaba Bailian VTON config (optional; null preserves existing values) */
        private Boolean vtonEnabled;
        private String vtonProvider;
        private String vtonModel;
        private String vtonApiKey;
        private Boolean clearVtonApiKey = false;
        private String vtonPublicBaseUrl;
    }

    @Data
    public static class TestRequest {
        private String baseUrl;
        private String apiKey;
        private String chatModel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigVo {
        private String providerName;
        private String baseUrl;
        private boolean hasApiKey;
        private String apiKeyMasked;
        private String visionModel;
        private String chatModel;
        private String imageModel;
        private String modelReferenceUrl;
        private boolean enabled;
        private boolean serverDefaultAvailable;
        private boolean vtonEnabled;
        private String vtonProvider;
        private String vtonModel;
        private boolean hasVtonApiKey;
        private String vtonApiKeyMasked;
        private boolean vtonKeyReusesMain;
        private String vtonPublicBaseUrl;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestResultVo {
        private boolean success;
        private long latencyMs;
        private String modelReply;
        private String message;
    }
}
