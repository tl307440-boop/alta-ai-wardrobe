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
import java.util.Locale;

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
            boolean vtonActive,
            String visionBaseUrl,
            String visionApiKey,
            boolean visionActive,
            String imageBaseUrl,
            String imageApiKey,
            boolean imageActive
    ) {}

    public AiConfigDto.ConfigVo getConfigVo(Long userId) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        boolean serverDefaultAvailable = defaultApiKey != null && !defaultApiKey.isBlank();
        if (entity == null) {
            boolean defaultIsDashScope = isDashScopeMain("dashscope", defaultBaseUrl);
            boolean defaultUnsupportedImg = isUnsupportedImageHost(defaultBaseUrl);
            boolean defaultVisionReady = serverDefaultAvailable && defaultVisionModel != null && !defaultVisionModel.isBlank();
            boolean defaultImageReady = serverDefaultAvailable && !defaultUnsupportedImg && defaultImageModel != null && !defaultImageModel.isBlank();
            return AiConfigDto.ConfigVo.builder()
                    .providerName("dashscope")
                    .baseUrl(defaultBaseUrl)
                    .hasApiKey(false)
                    .apiKeyMasked("")
                    .visionModel(defaultVisionModel)
                    .visionBaseUrl("")
                    .hasVisionApiKey(false)
                    .visionApiKeyMasked("")
                    .visionKeyReusesMain(serverDefaultAvailable)
                    .visionKeyReusesImage(false)
                    .visionReady(defaultVisionReady)
                    .chatModel(defaultChatModel)
                    .imageModel(defaultImageModel)
                    .imageBaseUrl("")
                    .hasImageApiKey(false)
                    .imageApiKeyMasked("")
                    .imageKeyReusesMain(serverDefaultAvailable)
                    .imageKeyReusesVision(false)
                    .imageReady(defaultImageReady)
                    .imageUnsupportedOnMain(defaultUnsupportedImg)
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
        boolean mainEnabled = entity.getEnabled() != null && entity.getEnabled() == 1;
        boolean mainIsDashScope = isDashScopeMain(entity.getProviderName(), entity.getBaseUrl());
        boolean mainAvailableForReuse = mainEnabled && (hasKey || serverDefaultAvailable);
        boolean vtonKeyReusesMain = !hasVtonKey && mainIsDashScope && mainAvailableForReuse;

        String rawMainBase = trimTrailingSlash(entity.getBaseUrl());
        String effectiveMainBase = (!hasKey && serverDefaultAvailable) ? trimTrailingSlash(defaultBaseUrl) : rawMainBase;

        String rawVisionBase = trimTrailingSlash(entity.getVisionBaseUrl());
        boolean hasVisionKey = entity.getVisionApiKeyEncrypted() != null && !entity.getVisionApiKeyEncrypted().isBlank();
        String rawImageBase = trimTrailingSlash(entity.getImageBaseUrl());
        boolean hasImageKey = entity.getImageApiKeyEncrypted() != null && !entity.getImageApiKeyEncrypted().isBlank();

        boolean visionInheritsMain = rawVisionBase.isBlank() || sameEndpoint(rawVisionBase, effectiveMainBase);
        boolean visionKeyReusesMain = !hasVisionKey && visionInheritsMain && mainAvailableForReuse;
        boolean visionKeyReusesImage = !hasVisionKey && !visionKeyReusesMain
                && !rawVisionBase.isBlank() && sameEndpoint(rawVisionBase, rawImageBase) && hasImageKey && mainEnabled;
        String effectiveVisionBase = rawVisionBase.isBlank() ? effectiveMainBase : rawVisionBase;
        boolean visionReady = mainEnabled && !effectiveVisionBase.isBlank()
                && ((hasVisionKey) || visionKeyReusesMain || visionKeyReusesImage)
                && entity.getVisionModel() != null && !entity.getVisionModel().isBlank();

        boolean imageInheritsMain = rawImageBase.isBlank() || sameEndpoint(rawImageBase, effectiveMainBase);
        boolean imageKeyReusesMain = !hasImageKey && imageInheritsMain && mainAvailableForReuse;
        boolean imageKeyReusesVision = !hasImageKey && !imageKeyReusesMain
                && !rawImageBase.isBlank() && sameEndpoint(rawImageBase, rawVisionBase) && hasVisionKey && mainEnabled;
        String effectiveImageBase = rawImageBase.isBlank() ? effectiveMainBase : rawImageBase;
        boolean imageUnsupportedOnMain = isUnsupportedImageHost(effectiveImageBase);
        boolean imageReady = mainEnabled && !imageUnsupportedOnMain && !effectiveImageBase.isBlank()
                && ((hasImageKey) || imageKeyReusesMain || imageKeyReusesVision)
                && imgModel != null && !imgModel.isBlank();

        return AiConfigDto.ConfigVo.builder()
                .providerName(entity.getProviderName())
                .baseUrl(entity.getBaseUrl())
                .hasApiKey(hasKey)
                .apiKeyMasked(hasKey ? entity.getApiKeyMasked() : "")
                .visionModel(entity.getVisionModel())
                .visionBaseUrl(rawVisionBase)
                .hasVisionApiKey(hasVisionKey)
                .visionApiKeyMasked(hasVisionKey ? entity.getVisionApiKeyMasked() : "")
                .visionKeyReusesMain(visionKeyReusesMain)
                .visionKeyReusesImage(visionKeyReusesImage)
                .visionReady(visionReady)
                .chatModel(entity.getChatModel())
                .imageModel(imgModel)
                .imageBaseUrl(rawImageBase)
                .hasImageApiKey(hasImageKey)
                .imageApiKeyMasked(hasImageKey ? entity.getImageApiKeyMasked() : "")
                .imageKeyReusesMain(imageKeyReusesMain)
                .imageKeyReusesVision(imageKeyReusesVision)
                .imageReady(imageReady)
                .imageUnsupportedOnMain(imageUnsupportedOnMain)
                .modelReferenceUrl(entity.getModelReferenceUrl())
                .enabled(mainEnabled)
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

        if (req.getVisionBaseUrl() != null) {
            String vb = trimTrailingSlash(req.getVisionBaseUrl());
            entity.setVisionBaseUrl(vb.isBlank() ? null : vb);
        }
        if (Boolean.TRUE.equals(req.getClearVisionApiKey())) {
            entity.setVisionApiKeyEncrypted(null);
            entity.setVisionApiKeyMasked(null);
        } else if (req.getVisionApiKey() != null && !req.getVisionApiKey().isBlank()) {
            String rawVisKey = req.getVisionApiKey().trim();
            entity.setVisionApiKeyEncrypted(cryptoService.encrypt(rawVisKey));
            entity.setVisionApiKeyMasked(cryptoService.mask(rawVisKey));
        }

        if (req.getImageBaseUrl() != null) {
            String ib = trimTrailingSlash(req.getImageBaseUrl());
            entity.setImageBaseUrl(ib.isBlank() ? null : ib);
        }
        if (Boolean.TRUE.equals(req.getClearImageApiKey())) {
            entity.setImageApiKeyEncrypted(null);
            entity.setImageApiKeyMasked(null);
        } else if (req.getImageApiKey() != null && !req.getImageApiKey().isBlank()) {
            String rawImgKey = req.getImageApiKey().trim();
            entity.setImageApiKeyEncrypted(cryptoService.encrypt(rawImgKey));
            entity.setImageApiKeyMasked(cryptoService.mask(rawImgKey));
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
     * User's saved encrypted keys take precedence and are isolated per endpoint host to prevent cross-host leaks.
     */
    public ResolvedAiCredentials resolveForUser(Long userId) {
        UserAiConfig entity = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        if (entity != null) {
            boolean enabled = entity.getEnabled() != null && entity.getEnabled() == 1;
            String imgModel = (entity.getImageModel() != null && !entity.getImageModel().isBlank())
                    ? entity.getImageModel().trim() : defaultImageModel;

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
                if (effectiveVision == null || effectiveVision.isBlank()) effectiveVision = defaultVisionModel;
                if (effectiveChat == null || effectiveChat.isBlank()) effectiveChat = defaultChatModel;
                mainActive = true;
            }

            String rawVisionBase = trimTrailingSlash(entity.getVisionBaseUrl());
            String visionDecrypted = enabled ? cryptoService.decrypt(entity.getVisionApiKeyEncrypted()) : null;
            String rawImageBase = trimTrailingSlash(entity.getImageBaseUrl());
            String imageDecrypted = enabled ? cryptoService.decrypt(entity.getImageApiKeyEncrypted()) : null;

            String effectiveVisionBase = !rawVisionBase.isBlank() ? rawVisionBase : effectiveBaseUrl;
            String effectiveVisionKey = resolveScopedKey(
                    visionDecrypted, rawVisionBase, effectiveBaseUrl, effectiveMainKey, rawImageBase, imageDecrypted);
            boolean visionActive = enabled
                    && !effectiveVisionBase.isBlank()
                    && effectiveVisionKey != null && !effectiveVisionKey.isBlank()
                    && effectiveVision != null && !effectiveVision.isBlank();

            String effectiveImageBase = !rawImageBase.isBlank() ? rawImageBase : effectiveBaseUrl;
            String effectiveImageKey = resolveScopedKey(
                    imageDecrypted, rawImageBase, effectiveBaseUrl, effectiveMainKey, rawVisionBase, visionDecrypted);
            boolean imageActive = enabled
                    && !isUnsupportedImageHost(effectiveImageBase)
                    && !effectiveImageBase.isBlank()
                    && effectiveImageKey != null && !effectiveImageKey.isBlank()
                    && imgModel != null && !imgModel.isBlank();

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
                    vtonActive,
                    effectiveVisionBase,
                    effectiveVisionKey,
                    visionActive,
                    effectiveImageBase,
                    effectiveImageKey,
                    imageActive
            );
        }
        if (defaultApiKey != null && !defaultApiKey.isBlank()) {
            String defBase = trimTrailingSlash(defaultBaseUrl);
            String defKey = defaultApiKey.trim();
            boolean defVisionActive = defaultVisionModel != null && !defaultVisionModel.isBlank();
            boolean defImageActive = !isUnsupportedImageHost(defBase)
                    && defaultImageModel != null && !defaultImageModel.isBlank();
            return new ResolvedAiCredentials(
                    defBase,
                    defKey,
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
                    false,
                    defBase,
                    defKey,
                    defVisionActive,
                    defBase,
                    defKey,
                    defImageActive
            );
        }
        String defBase = trimTrailingSlash(defaultBaseUrl);
        return new ResolvedAiCredentials(
                defBase,
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
                false,
                defBase,
                null,
                false,
                defBase,
                null,
                false
        );
    }

    /**
     * Resolves an optional override channel's key while guaranteeing that the main or sibling key is NEVER
     * leaked to a different custom host.
     */
    public static String resolveScopedKey(String dedicatedKey,
                                          String customBase,
                                          String mainBase,
                                          String mainKey,
                                          String siblingBase,
                                          String siblingKey) {
        if (dedicatedKey != null && !dedicatedKey.isBlank()) {
            return dedicatedKey.trim();
        }
        String normCustom = trimTrailingSlash(customBase);
        if (normCustom.isBlank() || sameEndpoint(normCustom, mainBase)) {
            return (mainKey != null && !mainKey.isBlank()) ? mainKey.trim() : null;
        }
        if (siblingKey != null && !siblingKey.isBlank() && sameEndpoint(normCustom, siblingBase)) {
            return siblingKey.trim();
        }
        return null;
    }

    public static boolean sameEndpoint(String a, String b) {
        String na = trimTrailingSlash(a);
        String nb = trimTrailingSlash(b);
        return !na.isBlank() && !nb.isBlank() && na.equalsIgnoreCase(nb);
    }

    public static boolean isUnsupportedImageHost(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.toLowerCase(Locale.ROOT).contains("api.deepseek.com");
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

    public static String trimTrailingSlash(String url) {
        if (url == null) return "";
        String res = url.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
