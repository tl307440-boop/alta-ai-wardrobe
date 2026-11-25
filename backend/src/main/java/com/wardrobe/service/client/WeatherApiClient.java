package com.wardrobe.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.model.dto.WeatherVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherApiClient {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Map<String, double[]> CITY_COORDS = Map.ofEntries(
            Map.entry("武汉", new double[]{30.5928, 114.3055}),
            Map.entry("北京", new double[]{39.9042, 116.4074}),
            Map.entry("上海", new double[]{31.2304, 121.4737}),
            Map.entry("杭州", new double[]{30.2741, 120.1551}),
            Map.entry("成都", new double[]{30.5728, 104.0668}),
            Map.entry("广州", new double[]{23.1291, 113.2644}),
            Map.entry("深圳", new double[]{22.5431, 114.0579}),
            Map.entry("南京", new double[]{32.0603, 118.7969}),
            Map.entry("西安", new double[]{34.3416, 108.9398}),
            Map.entry("重庆", new double[]{29.4316, 106.9123}),
            Map.entry("长沙", new double[]{28.2282, 112.9388}),
            Map.entry("苏州", new double[]{31.2989, 120.5853}),
            Map.entry("天津", new double[]{39.3434, 117.3616}),
            Map.entry("厦门", new double[]{24.4798, 118.0894}),
            Map.entry("青岛", new double[]{36.0671, 120.3826}),
            Map.entry("郑州", new double[]{34.7466, 113.6253}),
            Map.entry("合肥", new double[]{31.8206, 117.2272}),
            Map.entry("福州", new double[]{26.0745, 119.2965}),
            Map.entry("昆明", new double[]{24.8801, 102.8329}),
            Map.entry("哈尔滨", new double[]{45.8038, 126.5350}),
            Map.entry("沈阳", new double[]{41.8057, 123.4315}),
            Map.entry("大连", new double[]{38.9140, 121.6147}),
            Map.entry("三亚", new double[]{18.2528, 109.5119})
    );

    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${app.weather.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${app.weather.cache-minutes:30}")
    private long cacheMinutes;

    public WeatherVo getCurrentWeather(String city) {
        return getCurrentWeather(city, false);
    }

    public WeatherVo getCurrentWeather(String city, boolean forceRefresh) {
        String targetCity = normalizeCityName(city);
        if (!forceRefresh) {
            CacheEntry cached = cache.get(targetCity);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.weather();
            }
        }

        try {
            double[] coords = resolveCoords(targetCity);
            if (coords != null) {
                WeatherVo live = fetchOpenMeteo(targetCity, coords[0], coords[1]);
                if (live != null) {
                    cache.put(targetCity, new CacheEntry(live, Instant.now().plusSeconds(cacheMinutes * 60)));
                    return live;
                }
            }
        } catch (Exception e) {
            log.warn("Open-Meteo weather fetch failed for city={}, falling back to default: {}", targetCity, e.getMessage());
        }

        WeatherVo fallback = WeatherVo.builder()
                .city(targetCity)
                .temp(18)
                .apparentTemp(18)
                .humidity(55)
                .windSpeed(8.0)
                .condition("多云")
                .icon("cloud-sun")
                .tip("适宜叠穿")
                .seasonHint("秋")
                .source("离线降级兜底")
                .updatedAt(LocalTime.now().format(TIME_FMT))
                .live(false)
                .build();
        cache.put(targetCity, new CacheEntry(fallback, Instant.now().plusSeconds(300)));
        return fallback;
    }

    private String normalizeCityName(String city) {
        if (city == null || city.isBlank()) return "武汉";
        String cleaned = city.trim();
        if (cleaned.length() > 2 && cleaned.endsWith("市")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private double[] resolveCoords(String city) {
        if (CITY_COORDS.containsKey(city)) {
            return CITY_COORDS.get(city);
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&name="
                    + URLEncoder.encode(city, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode results = root.path("results");
                if (results.isArray() && !results.isEmpty()) {
                    JsonNode first = results.get(0);
                    return new double[]{first.path("latitude").asDouble(), first.path("longitude").asDouble()};
                }
            }
        } catch (Exception e) {
            log.debug("Geocoding lookup failed for {}: {}", city, e.getMessage());
        }
        return CITY_COORDS.get("武汉");
    }

    private WeatherVo fetchOpenMeteo(String city, double lat, double lon) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&timezone=Asia%%2FShanghai",
                lat, lon);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        JsonNode current = objectMapper.readTree(resp.body()).path("current");
        int temp = (int) Math.round(current.path("temperature_2m").asDouble(18.0));
        int apparentTemp = (int) Math.round(current.path("apparent_temperature").asDouble(temp));
        int humidity = (int) Math.round(current.path("relative_humidity_2m").asDouble(55.0));
        double windSpeed = Math.round(current.path("wind_speed_10m").asDouble(0.0) * 10.0) / 10.0;
        int code = current.path("weather_code").asInt(2);

        String condition;
        String icon;
        if (code == 0) {
            condition = "晴朗";
            icon = "sun";
        } else if (code <= 3) {
            condition = "多云";
            icon = "cloud-sun";
        } else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) {
            condition = "小雨";
            icon = "cloud-rain";
        } else if (code >= 71 && code <= 77) {
            condition = "降雪";
            icon = "cloud-rain";
        } else {
            condition = "阴天";
            icon = "cloud-sun";
        }

        String tip;
        String seasonHint;
        if (temp >= 25) {
            tip = "轻薄透气";
            seasonHint = "夏";
        } else if (temp <= 11) {
            tip = "保暖防风";
            seasonHint = "冬";
        } else {
            tip = "适宜叠穿";
            seasonHint = "秋";
        }

        return WeatherVo.builder()
                .city(city)
                .temp(temp)
                .apparentTemp(apparentTemp)
                .humidity(humidity)
                .windSpeed(windSpeed)
                .condition(condition)
                .icon(icon)
                .tip(tip)
                .seasonHint(seasonHint)
                .source("Open-Meteo 实时气象")
                .updatedAt(LocalTime.now().format(TIME_FMT))
                .live(true)
                .build();
    }

    private record CacheEntry(WeatherVo weather, Instant expiresAt) {}
}
