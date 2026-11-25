package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.WeatherVo;
import com.wardrobe.model.entity.UserPreference;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.PreferenceService;
import com.wardrobe.service.client.WeatherApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {
    private final WeatherApiClient weatherApiClient;
    private final PreferenceService preferenceService;

    @GetMapping("/current")
    public Result<WeatherVo> current(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "false") boolean refresh) {
        String targetCity = city;
        if (targetCity == null || targetCity.isBlank()) {
            UserPreference pref = preferenceService.getOrCreateEntity(UserContextHolder.getUserId());
            targetCity = pref.getDefaultCity();
        }
        return Result.ok(weatherApiClient.getCurrentWeather(targetCity, refresh));
    }
}
