package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.PreferenceDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {
    private final PreferenceService preferenceService;

    @GetMapping
    public Result<PreferenceDto.PreferenceVo> get() {
        return Result.ok(preferenceService.getPreferenceWithAnalytics(UserContextHolder.getUserId()));
    }

    @PutMapping
    public Result<PreferenceDto.PreferenceVo> update(@RequestBody PreferenceDto.UpdateRequest req) {
        return Result.ok(preferenceService.updatePreference(UserContextHolder.getUserId(), req));
    }
}
