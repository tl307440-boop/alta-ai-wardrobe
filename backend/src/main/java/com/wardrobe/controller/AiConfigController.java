package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.AiConfigDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.AiConfigService;
import com.wardrobe.service.StorageService;
import com.wardrobe.service.client.MultimodalLlmClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
public class AiConfigController {
    private final AiConfigService aiConfigService;
    private final MultimodalLlmClient llmClient;
    private final StorageService storageService;

    @GetMapping
    public Result<AiConfigDto.ConfigVo> get() {
        return Result.ok(aiConfigService.getConfigVo(UserContextHolder.getUserId()));
    }

    @PutMapping
    public Result<AiConfigDto.ConfigVo> save(@Valid @RequestBody AiConfigDto.SaveRequest req) {
        return Result.ok("模型 API 配置已加密保存并在服务端即时生效", aiConfigService.saveConfig(UserContextHolder.getUserId(), req));
    }

    @PostMapping("/test")
    public Result<AiConfigDto.TestResultVo> test(@RequestBody(required = false) AiConfigDto.TestRequest req) {
        return Result.ok(llmClient.testConnection(UserContextHolder.getUserId(), req));
    }

    @PostMapping("/model-reference")
    public Result<AiConfigDto.ConfigVo> uploadModelReference(@RequestParam("file") MultipartFile file) {
        Long userId = UserContextHolder.getUserId();
        StorageService.StoredImage stored = storageService.validateAndStoreImage(userId, file, "model_ref");
        return Result.ok("本人/模特参考图已上传，生成上身预览时将优先参考该形象",
                aiConfigService.updateModelReference(userId, stored.url()));
    }

    @DeleteMapping("/model-reference")
    public Result<AiConfigDto.ConfigVo> clearModelReference() {
        return Result.ok("已清除模特参考图",
                aiConfigService.updateModelReference(UserContextHolder.getUserId(), null));
    }
}
