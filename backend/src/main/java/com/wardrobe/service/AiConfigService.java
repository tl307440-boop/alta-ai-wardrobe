package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.mapper.UserAiConfigMapper;
import com.wardrobe.model.dto.AiConfigDto;
import com.wardrobe.model.entity.UserAiConfig;
import com.wardrobe.security.AiKeyCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiConfigService {
    private final UserAiConfigMapper aiConfigMapper;
    private final AiKeyCryptoService cryptoService;

    @Value("${app.ai.default-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @Value("${app.ai.default-api-key:}")
    private String defaultApiKey;

    @Value("${app.ai.default-vision-model:qwen-vl-max}")
    private String defaultVisionModel;

    @Value("${app.ai.default-chat-model:qwen-plus}")
    private String defaultChatModel;

    @Value("${app.ai.default-image-model:gpt-image-1}")
    private String defaultImageModel;

    public record ResolvedAiCredentials(
            String baseUrl,
            String apiKey,
            String visionModel,
            String chatModel,
            String imageModel,
            String modelReferenceUrl,
            boolean active,
            boolean vtonEnabled,
            String vtonProvider,
            String vtonModel,
            String vtonApiKey,
            String vtonPublicBaseUrl,
            boolean vtonActive
    ) {}

    public AiConfigDto.ConfigVo getConfigVo(Long userId) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        boolean serverDefaultAvailable = defaultApiKey != null && !defaultApiKey.isBlank();
        if (entity == null) {
            boolean defaultIsDashScope = isDashScopeMain("dashscope", defaultBaseUrl);
            return AiConfigDto.ConfigVo.builder()
                    .providerName("dashscope")
                    .baseUrl(defaultBaseUrl)
                    .hasApiKey(false)
                    .apiKeyMasked("")
                    .visionModel(defaultVisionModel)
                    .chatModel(defaultChatModel)
                    .imageModel(defaultImageModel)
                    .modelReferenceUrl(null)
                    .enabled(true)
                    .serverDefaultAvailable(serverDefaultAvailable)
                    .vtonEnabled(false)
                    .vtonProvider("dashscope")
                    .vtonModel(com.wardrobe.service.client.DashScopeVtonClient.DEFAULT_MODEL)
                    .hasVtonApiKey(false)
                    .vtonApiKeyMasked("")
                    .vtonKeyReusesMain(defaultIsDashScope && serverDefaultAvailable)
                    .vtonPublicBaseUrl("")
                    .updatedAt(null)
                    .build();
        }
        boolean hasKey = entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
        String imgModel = (entity.getImageModel() != null && !entity.getImageModel().isBlank())
                ? entity.getImageModel() : defaultImageModel;
        boolean vtonEnabled = entity.getVtonEnabled() != null && entity.getVtonEnabled() == 1;
        String vtonProvider = (entity.getVtonProvider() != null && !entity.getVtonProvider().isBlank())
                ? entity.getVtonProvider() : "dashscope";
        String vtonModel = normalizeVtonModel(entity.getVtonModel());
        boolean hasVtonKey = entity.getVtonApiKeyEncrypted() != null && !entity.getVtonApiKeyEncrypted().isBlank();
        boolean mainEnabled = entity.getEnabled() == null || entity.getEnabled() == 1;
        boolean mainIsDashScope = isDashScopeMain(entity.getProviderName(), entity.getBaseUrl());
        boolean mainAvailableForReuse = (mainEnabled && hasKey) || serverDefaultAvailable;
        boolean vtonKeyReusesMain = !hasVtonKey && mainIsDashScope && mainAvailableForReuse;

        return AiConfigDto.ConfigVo.builder()
                .providerName(entity.getProviderName())
                .baseUrl(entity.getBaseUrl())
                .hasApiKey(hasKey)
                .apiKeyMasked(hasKey ? entity.getApiKeyMasked() : "")
                .visionModel(entity.getVisionModel())
                .chatModel(entity.getChatModel())
                .imageModel(imgModel)
                .modelReferenceUrl(entity.getModelReferenceUrl())
                .enabled(entity.getEnabled() != null && entity.getEnabled() == 1)
                .serverDefaultAvailable(serverDefaultAvailable)
                .vtonEnabled(vtonEnabled)
                .vtonProvider(vtonProvider)
                .vtonModel(vtonModel)
                .hasVtonApiKey(hasVtonKey)
                .vtonApiKeyMasked(hasVtonKey ? entity.getVtonApiKeyMasked() : "")
                .vtonKeyReusesMain(vtonKeyReusesMain)
                .vtonPublicBaseUrl(entity.getVtonPublicBaseUrl() != null ? entity.getVtonPublicBaseUrl() : "")
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public AiConfigDto.ConfigVo saveConfig(Long userId, AiConfigDto.SaveRequest req) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        boolean isNew = (entity == null);
        if (isNew) {
            entity = new UserAiConfig();
            entity.setUserId(userId);
        }

        entity.setProviderName(req.getProviderName() != null && !req.getProviderName().isBlank()
                ? req.getProviderName().trim() : "custom");
        entity.setBaseUrl(trimTrailingSlash(req.getBaseUrl().trim()));
        entity.setVisionModel(req.getVisionModel().trim());
        entity.setChatModel(req.getChatModel().trim());
        entity.setImageModel(req.getImageModel() != null && !req.getImageModel().isBlank()
                ? req.getImageModel().trim() : defaultImageModel);
        entity.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);

        if (Boolean.TRUE.equals(req.getClearApiKey())) {
            entity.setApiKeyEncrypted(null);
            entity.setApiKeyMasked(null);
        } else if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            String rawKey = req.getApiKey().trim();
            entity.setApiKeyEncrypted(cryptoService.encrypt(rawKey));
            entity.setApiKeyMasked(cryptoService.mask(rawKey));
        }

        if (req.getVtonEnabled() != null) {
            entity.setVtonEnabled(Boolean.TRUE.equals(req.getVtonEnabled()) ? 1 : 0);
        } else if (isNew || entity.getVtonEnabled() == null) {
            entity.setVtonEnabled(0);
        }
        if (req.getVtonProvider() != null && !req.getVtonProvider().isBlank()) {
            entity.setVtonProvider("dashscope");
        } else if (isNew || entity.getVtonProvider() == null || entity.getVtonProvider().isBlank()) {
            entity.setVtonProvider("dashscope");
        }
        if (req.getVtonModel() != null && !req.getVtonModel().isBlank()) {
            entity.setVtonModel(normalizeVtonModel(req.getVtonModel()));
        } else if (isNew || entity.getVtonModel() == null || entity.getVtonModel().isBlank()) {
            entity.setVtonModel(com.wardrobe.service.client.DashScopeVtonClient.DEFAULT_MODEL);
        } else {
            entity.setVtonModel(normalizeVtonModel(entity.getVtonModel()));
        }
        if (Boolean.TRUE.equals(req.getClearVtonApiKey())) {
            entity.setVtonApiKeyEncrypted(null);
            entity.setVtonApiKeyMasked(null);
        } else if (req.getVtonApiKey() != null && !req.getVtonApiKey().isBlank()) {
            String rawVtonKey = req.getVtonApiKey().trim();
            entity.setVtonApiKeyEncrypted(cryptoService.encrypt(rawVtonKey));
            entity.setVtonApiKeyMasked(cryptoService.mask(rawVtonKey));
        }
        if (req.getVtonPublicBaseUrl() != null) {
            String pub = trimTrailingSlash(req.getVtonPublicBaseUrl());
            entity.setVtonPublicBaseUrl(pub.isBlank() ? null : pub);
        }

        entity.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.updateById(entity);
        }
        return getConfigVo(userId);
    }

    public AiConfigDto.ConfigVo updateModelReference(Long userId, String modelReferenceUrl) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        boolean isNew = (entity == null);
        if (isNew) {
            entity = new UserAiConfig();
            entity.setUserId(userId);
            entity.setProviderName("openai-relay");
            entity.setBaseUrl(defaultBaseUrl);
            entity.setVisionModel(defaultVisionModel);
            entity.setChatModel(defaultChatModel);
            entity.setImageModel(defaultImageModel);
            entity.setEnabled(1);
            entity.setVtonEnabled(0);
            entity.setVtonProvider("dashscope");
            entity.setVtonModel(com.wardrobe.service.client.DashScopeVtonClient.DEFAULT_MODEL);
        }
        entity.setModelReferenceUrl(modelReferenceUrl);
        entity.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.updateById(entity);
        }
        return getConfigVo(userId);
    }

    /**
     * Resolves active server-side credentials for the current user.
     * User's saved encrypted key takes precedence and takes effect immediately on the server.
     */
    public ResolvedAiCredentials resolveForUser(Long userId) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        if (entity != null) {
            boolean enabled = entity.getEnabled() != null && entity.getEnabled() == 1;
            String imgModel = (entity.getImageModel() != null && !entity.getImageModel().isBlank())
                    ? entity.getImageModel() : defaultImageModel;

            String mainDecrypted = enabled ? cryptoService.decrypt(entity.getApiKeyEncrypted()) : null;
            String effectiveMainKey = null;
            String effectiveBaseUrl = trimTrailingSlash(entity.getBaseUrl());
            String effectiveVision = entity.getVisionModel();
            String effectiveChat = entity.getChatModel();
            boolean mainActive = false;

            if (enabled && mainDecrypted != null && !mainDecrypted.isBlank()) {
                effectiveMainKey = mainDecrypted;
                mainActive = true;
            } else if (enabled && defaultApiKey != null && !defaultApiKey.isBlank()) {
                effectiveMainKey = defaultApiKey.trim();
                effectiveBaseUrl = trimTrailingSlash(defaultBaseUrl);
                effectiveVision = defaultVisionModel;
                effectiveChat = defaultChatModel;
                mainActive = true;
            }

            boolean vtonEnabled = entity.getVtonEnabled() != null && entity.getVtonEnabled() == 1;
            String vtonProvider = (entity.getVtonProvider() != null && !entity.getVtonProvider().isBlank())
                    ? entity.getVtonProvider() : "dashscope";
            String vtonModel = normalizeVtonModel(entity.getVtonModel());
            String vtonPub = trimTrailingSlash(entity.getVtonPublicBaseUrl());

            String vtonDecrypted = cryptoService.decrypt(entity.getVtonApiKeyEncrypted());
            String effectiveVtonKey = (vtonDecrypted != null && !vtonDecrypted.isBlank()) ? vtonDecrypted : null;
            if (effectiveVtonKey == null && isDashScopeMain(entity.getProviderName(), entity.getBaseUrl())) {
                if (effectiveMainKey != null && !effectiveMainKey.isBlank()) {
                    effectiveVtonKey = effectiveMainKey;
                } else if (defaultApiKey != null && !defaultApiKey.isBlank()) {
                    effectiveVtonKey = defaultApiKey.trim();
                }
            }
            boolean vtonActive = vtonEnabled
                    && "dashscope".equalsIgnoreCase(vtonProvider)
                    && effectiveVtonKey != null && !effectiveVtonKey.isBlank();

            return new ResolvedAiCredentials(
                    effectiveBaseUrl,
                    effectiveMainKey,
                    effectiveVision,
                    effectiveChat,
                    imgModel,
                    entity.getModelReferenceUrl(),
                    mainActive,
                    vtonEnabled,
                    vtonProvider,
                    vtonModel,
                    effectiveVtonKey,
                    vtonPub.isBlank() ? null : vtonPub,
                    vtonActive
            );
        }
        if (defaultApiKey != null && !defaultApiKey.isBlank()) {
            return new ResolvedAiCredentials(
                    trimTrailingSlash(defaultBaseUrl),
                    defaultApiKey.trim(),
                    defaultVisionModel,
                    defaultChatModel,
                    defaultImageModel,
                    null,
                    true,
                    false,
                    "dashscope",
                    com.wardrobe.service.client.DashScopeVtonClient.DEFAULT_MODEL,
                    null,
                    null,
                    false
            );
        }
        return new ResolvedAiCredentials(
                trimTrailingSlash(defaultBaseUrl),
                null,
                defaultVisionModel,
                defaultChatModel,
                defaultImageModel,
                null,
                false,
                false,
                "dashscope",
                com.wardrobe.service.client.DashScopeVtonClient.DEFAULT_MODEL,
                null,
                null,
                false
        );
    }

    private boolean isDashScopeMain(String providerName, String baseUrl) {
        if ("dashscope".equalsIgnoreCase(providerName)) {
            return true;
        }
        return baseUrl != null && baseUrl.toLowerCase().contains("dashscope.aliyuncs.com");
    }

    public static String normalizeVtonModel(String raw) {
        return com.wardrobe.service.client.DashScopeVtonClient.normalizeModel(raw);
    }

    private String trimTrailingSlash(String url) {
        if (url == null) return "";
        String res = url.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
