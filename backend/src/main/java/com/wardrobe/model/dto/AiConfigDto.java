package com.wardrobe.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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

        /** Optional dedicated Vision Base URL (blank/null inherits main baseUrl) */
        private String visionBaseUrl;

        /** Optional dedicated Vision API Key (blank preserves existing; inherited when same host) */
        private String visionApiKey;

        private Boolean clearVisionApiKey = false;

        @NotBlank(message = "搭配推理模型名不能为空")
        private String chatModel;

        /** Optional OpenAI-compatible image generation / try-on model name */
        private String imageModel = "gpt-image-1";

        /** Optional dedicated General Image Base URL (blank/null inherits main baseUrl) */
        private String imageBaseUrl;

        /** Optional dedicated General Image API Key (blank preserves existing; inherited when same host) */
        private String imageApiKey;

        private Boolean clearImageApiKey = false;

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
        /** Optional channel filter: all (default), chat, vision, image, vton */
        private String channel;
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        private String visionBaseUrl;
        private String visionApiKey;
        private String visionModel;
        private String imageBaseUrl;
        private String imageApiKey;
        private String imageModel;
        private Boolean vtonEnabled;
        private String vtonApiKey;
        private String vtonModel;
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
        private String visionBaseUrl;
        private boolean hasVisionApiKey;
        private String visionApiKeyMasked;
        private boolean visionKeyReusesMain;
        private boolean visionKeyReusesImage;
        private boolean visionReady;
        private String chatModel;
        private String imageModel;
        private String imageBaseUrl;
        private boolean hasImageApiKey;
        private String imageApiKeyMasked;
        private boolean imageKeyReusesMain;
        private boolean imageKeyReusesVision;
        private boolean imageReady;
        private boolean imageUnsupportedOnMain;
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
    public static class ChannelTestResult {
        private String channel;
        private String label;
        private String endpoint;
        private String model;
        private boolean success;
        private boolean skipped;
        private long latencyMs;
        private String message;
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
        private List<ChannelTestResult> channels;
    }
}
