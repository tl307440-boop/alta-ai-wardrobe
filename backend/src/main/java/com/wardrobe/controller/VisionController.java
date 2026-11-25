package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.VisionDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.VisionRecognitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/vision")
@RequiredArgsConstructor
public class VisionController {
    private final VisionRecognitionService visionService;

    @PostMapping("/recognize-item")
    public Result<VisionDto.ItemRecognitionVo> recognizeItem(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "cutoutFile", required = false) MultipartFile cutoutFile,
            @RequestParam(value = "sourceType", defaultValue = "photo") String sourceType) {
        return Result.ok(visionService.recognizeItem(UserContextHolder.getUserId(), file, cutoutFile, sourceType));
    }

    @PostMapping("/analyze-inspiration")
    public Result<VisionDto.InspirationAnalyzeVo> analyzeInspiration(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "presetId", required = false) String presetId,
            @RequestParam(value = "userQuery", required = false) String userQuery,
            @RequestParam(value = "budgetLimit", required = false) BigDecimal budgetLimit) {
        return Result.ok(visionService.analyzeInspiration(
                UserContextHolder.getUserId(), file, presetId, userQuery, budgetLimit));
    }
}
