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
            boolean active
    ) {}

    public AiConfigDto.ConfigVo getConfigVo(Long userId) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        boolean serverDefaultAvailable = defaultApiKey != null && !defaultApiKey.isBlank();
        if (entity == null) {
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
                    .updatedAt(null)
                    .build();
        }
        boolean hasKey = entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
        String imgModel = (entity.getImageModel() != null && !entity.getImageModel().isBlank())
                ? entity.getImageModel() : defaultImageModel;
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
            if (!enabled) {
                return new ResolvedAiCredentials(entity.getBaseUrl(), null, entity.getVisionModel(),
                        entity.getChatModel(), imgModel, entity.getModelReferenceUrl(), false);
            }
            String decrypted = cryptoService.decrypt(entity.getApiKeyEncrypted());
            if (decrypted != null && !decrypted.isBlank()) {
                return new ResolvedAiCredentials(
                        trimTrailingSlash(entity.getBaseUrl()),
                        decrypted,
                        entity.getVisionModel(),
                        entity.getChatModel(),
                        imgModel,
                        entity.getModelReferenceUrl(),
                        true
                );
            }
            if (defaultApiKey != null && !defaultApiKey.isBlank()) {
                return new ResolvedAiCredentials(
                        trimTrailingSlash(defaultBaseUrl),
                        defaultApiKey.trim(),
                        defaultVisionModel,
                        defaultChatModel,
                        imgModel,
                        entity.getModelReferenceUrl(),
                        true
                );
            }
            return new ResolvedAiCredentials(
                    trimTrailingSlash(entity.getBaseUrl()),
                    null,
                    entity.getVisionModel(),
                    entity.getChatModel(),
                    imgModel,
                    entity.getModelReferenceUrl(),
                    false
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
                    true
            );
        }
        return new ResolvedAiCredentials(
                trimTrailingSlash(defaultBaseUrl),
                null,
                defaultVisionModel,
                defaultChatModel,
                defaultImageModel,
                null,
                false
        );
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
