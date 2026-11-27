package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.OutfitHistoryMapper;
import com.wardrobe.mapper.WardrobeItemMapper;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.model.dto.WardrobeDto;
import com.wardrobe.model.entity.OutfitHistory;
import com.wardrobe.model.entity.WardrobeItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WardrobeService {
    private static final Map<String, String> SEASON_CN_TO_EN = Map.of(
            "春", "spring", "夏", "summer", "秋", "autumn", "冬", "winter"
    );
    private static final Map<String, String> SEASON_EN_TO_CN = Map.of(
            "spring", "春", "summer", "夏", "autumn", "秋", "winter", "冬"
    );

    private final WardrobeItemMapper wardrobeItemMapper;
    private final OutfitHistoryMapper outfitHistoryMapper;
    private final JsonUtils jsonUtils;

    public List<WardrobeDto.ItemVo> listItems(Long userId, String category, String color, String season, String keyword) {
        LambdaQueryWrapper<WardrobeItem> qw = new LambdaQueryWrapper<WardrobeItem>()
                .eq(WardrobeItem::getUserId, userId)
                .orderByDesc(WardrobeItem::getCreatedAt)
                .orderByDesc(WardrobeItem::getId);

        if (category != null && !category.isBlank() && !"all".equalsIgnoreCase(category)) {
            qw.eq(WardrobeItem::getCategory, category.trim());
        }
        if (color != null && !color.isBlank() && !"all".equalsIgnoreCase(color)) {
            qw.eq(WardrobeItem::getColor, color.trim());
        }

        List<WardrobeItem> items = wardrobeItemMapper.selectList(qw);
        return items.stream()
                .filter(it -> matchesSeason(it, season))
                .filter(it -> matchesKeyword(it, keyword))
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    public WardrobeDto.ItemVo createItem(Long userId, WardrobeDto.CreateItemRequest req) {
        WardrobeItem item = new WardrobeItem();
        item.setUserId(userId);
        item.setName(req.getName().trim());
        String tagsJoined = req.getStyleTags() != null ? String.join(" ", req.getStyleTags()) : "";
        item.setCategory(refineCategory(req.getCategory(), req.getSubCategory(), item.getName(), tagsJoined));
        item.setSubCategory(req.getSubCategory());
        item.setColor(req.getColor().trim());
        item.setColorHex(req.getColorHex() != null && !req.getColorHex().isBlank() ? req.getColorHex() : "#EFECE6");
        item.setSeason(encodeSeasons(req.getSeasons()));
        item.setThickness(req.getThickness() != null ? req.getThickness() : inferThickness(req.getSeasons(), item.getCategory()));
        List<String> tags = req.getStyleTags() != null && !req.getStyleTags().isEmpty()
                ? req.getStyleTags() : List.of("日常百搭");
        item.setStyleTags(jsonUtils.toJson(tags));
        item.setMaterial(req.getMaterial());
        String svgType = (req.getSvgType() != null && !req.getSvgType().isBlank())
                ? req.getSvgType() : inferSvgType(item.getCategory(), item.getName());
        item.setSvgType(svgType);
        item.setOriginalImageUrl(req.getOriginalImageUrl());
        String processedUrl = (req.getProcessedImageUrl() != null && !req.getProcessedImageUrl().isBlank())
                ? req.getProcessedImageUrl() : ("svg://" + svgType);
        item.setProcessedImageUrl(processedUrl);
        item.setSourceType(req.getSourceType() != null ? req.getSourceType() : "photo");
        item.setAiRawResult(req.getAiRawResult());
        item.setIsManuallyCorrected(Boolean.TRUE.equals(req.getIsManuallyCorrected()) ? 1 : 0);
        if (req.getPrice() != null && req.getPrice().compareTo(BigDecimal.ZERO) >= 0) {
            item.setPrice(req.getPrice().setScale(2, RoundingMode.HALF_UP));
        }
        item.setWearCount(0);
        item.setRemark(req.getRemark());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        wardrobeItemMapper.insert(item);
        return toVo(item);
    }

    public WardrobeDto.ItemDetailVo getItemDetail(Long userId, Long itemId) {
        WardrobeItem item = requireOwnedItem(userId, itemId);
        List<OutfitHistory> histories = outfitHistoryMapper.selectList(
                new LambdaQueryWrapper<OutfitHistory>()
                        .eq(OutfitHistory::getUserId, userId)
                        .orderByDesc(OutfitHistory::getWearDate));

        List<OutfitDto.HistoryVo> related = histories.stream()
                .filter(h -> jsonUtils.toLongList(h.getItemIds()).contains(itemId))
                .map(h -> OutfitDto.HistoryVo.builder()
                        .id(h.getId())
                        .planId(h.getPlanId())
                        .themeTitle(h.getThemeTitle())
                        .wearDate(h.getWearDate())
                        .sceneTag(h.getSceneTag())
                        .weatherInfo(h.getWeatherInfo())
                        .weatherTemp(h.getWeatherTemp())
                        .weatherIcon(h.getWeatherIcon())
                        .itemIds(jsonUtils.toLongList(h.getItemIds()))
                        .aiReasoning(h.getAiReasoning())
                        .feedbackScore(h.getFeedbackScore())
                        .notes(h.getNotes())
                        .build())
                .collect(Collectors.toList());

        return WardrobeDto.ItemDetailVo.builder()
                .item(toVo(item))
                .relatedHistory(related)
                .build();
    }

    public WardrobeDto.ItemVo updateItem(Long userId, Long itemId, WardrobeDto.UpdateItemRequest req) {
        WardrobeItem item = requireOwnedItem(userId, itemId);
        if (req.getName() != null && !req.getName().isBlank()) item.setName(req.getName().trim());
        if (req.getCategory() != null && !req.getCategory().isBlank()) item.setCategory(normalizeCategory(req.getCategory()));
        if (req.getSubCategory() != null) item.setSubCategory(req.getSubCategory());
        if (req.getColor() != null && !req.getColor().isBlank()) item.setColor(req.getColor().trim());
        if (req.getColorHex() != null && !req.getColorHex().isBlank()) item.setColorHex(req.getColorHex().trim());
        if (req.getSeasons() != null && !req.getSeasons().isEmpty()) item.setSeason(encodeSeasons(req.getSeasons()));
        if (req.getThickness() != null) item.setThickness(req.getThickness());
        if (req.getStyleTags() != null) item.setStyleTags(jsonUtils.toJson(req.getStyleTags()));
        if (req.getMaterial() != null) item.setMaterial(req.getMaterial());
        if (req.getPrice() != null && req.getPrice().compareTo(BigDecimal.ZERO) >= 0) {
            item.setPrice(req.getPrice().setScale(2, RoundingMode.HALF_UP));
        }
        if (req.getRemark() != null) item.setRemark(req.getRemark());
        if (req.getProcessedImageUrl() != null && isSafeItemImageUrl(userId, req.getProcessedImageUrl())) {
            item.setProcessedImageUrl(req.getProcessedImageUrl().trim());
        }
        if (req.getOriginalImageUrl() != null && isSafeItemImageUrl(userId, req.getOriginalImageUrl())) {
            item.setOriginalImageUrl(req.getOriginalImageUrl().trim());
        }
        item.setIsManuallyCorrected(1);
        item.setUpdatedAt(LocalDateTime.now());
        wardrobeItemMapper.updateById(item);
        return toVo(item);
    }

    private boolean isSafeItemImageUrl(Long userId, String url) {
        String trimmed = url.trim();
        return trimmed.startsWith("/uploads/user_" + userId + "/")
                || trimmed.startsWith("/alta/")
                || trimmed.startsWith("svg://");
    }

    public void deleteItem(Long userId, Long itemId) {
        WardrobeItem item = requireOwnedItem(userId, itemId);
        wardrobeItemMapper.deleteById(item.getId());
    }

    public WardrobeItem requireOwnedItem(Long userId, Long itemId) {
        WardrobeItem item = wardrobeItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到对应衣物单品");
        }
        if (!Objects.equals(item.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return item;
    }

    public WardrobeDto.ItemVo toVo(WardrobeItem entity) {
        if (entity == null) return null;
        int wear = entity.getWearCount() != null ? entity.getWearCount() : 0;
        BigDecimal price = entity.getPrice();
        BigDecimal cpw = null;
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            cpw = wear > 0
                    ? price.divide(BigDecimal.valueOf(wear), 2, RoundingMode.HALF_UP)
                    : price.setScale(2, RoundingMode.HALF_UP);
        }
        return WardrobeDto.ItemVo.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .name(entity.getName())
                .category(entity.getCategory())
                .subCategory(entity.getSubCategory())
                .color(entity.getColor())
                .colorHex(entity.getColorHex() != null ? entity.getColorHex() : "#EFECE6")
                .seasons(decodeSeasonsToCn(entity.getSeason()))
                .thickness(entity.getThickness())
                .styleTags(jsonUtils.toStringList(entity.getStyleTags()))
                .material(entity.getMaterial())
                .svgType(entity.getSvgType() != null ? entity.getSvgType() : inferSvgType(entity.getCategory(), entity.getName()))
                .originalImageUrl(entity.getOriginalImageUrl())
                .processedImageUrl(entity.getProcessedImageUrl())
                .sourceType(entity.getSourceType())
                .isManuallyCorrected(entity.getIsManuallyCorrected() != null && entity.getIsManuallyCorrected() == 1)
                .price(price)
                .costPerWear(cpw)
                .wearCount(wear)
                .lastWornDate(entity.getLastWornDate())
                .remark(entity.getRemark())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private boolean matchesSeason(WardrobeItem item, String season) {
        if (season == null || season.isBlank() || "all".equalsIgnoreCase(season)) return true;
        String target = SEASON_CN_TO_EN.getOrDefault(season.trim(), season.trim().toLowerCase());
        return item.getSeason() != null && item.getSeason().toLowerCase().contains(target);
    }

    private boolean matchesKeyword(WardrobeItem item, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String kw = keyword.trim().toLowerCase();
        String hay = String.join(" ",
                Objects.toString(item.getName(), ""),
                Objects.toString(item.getColor(), ""),
                Objects.toString(item.getCategory(), ""),
                Objects.toString(item.getSubCategory(), ""),
                Objects.toString(item.getMaterial(), ""),
                Objects.toString(item.getStyleTags(), "")
        ).toLowerCase();
        return hay.contains(kw);
    }

    public String encodeSeasons(List<String> seasons) {
        if (seasons == null || seasons.isEmpty()) return "spring,autumn";
        return seasons.stream()
                .filter(Objects::nonNull)
                .map(s -> SEASON_CN_TO_EN.getOrDefault(s.trim(), s.trim().toLowerCase()))
                .distinct()
                .collect(Collectors.joining(","));
    }

    public List<String> decodeSeasonsToCn(String seasonStr) {
        if (seasonStr == null || seasonStr.isBlank()) return List.of("春", "秋");
        return Arrays.stream(seasonStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> SEASON_EN_TO_CN.getOrDefault(s.toLowerCase(), s))
                .collect(Collectors.toList());
    }

    public String normalizeCategory(String cat) {
        if (cat == null) return "top";
        String c = cat.trim().toLowerCase();
        return switch (c) {
            case "outerwear", "外套" -> "outerwear";
            case "top", "上衣", "内搭" -> "top";
            case "dress", "连衣裙", "连体裤", "连体衣", "礼服", "礼服裙", "jsk" -> "dress";
            case "bottom", "下装", "裤子", "半身裙", "裙子" -> "bottom";
            case "shoes", "鞋履", "鞋子", "靴子" -> "shoes";
            case "bag", "bags", "包袋", "包包", "手袋", "箱包" -> "bag";
            case "accessory", "accessories", "配饰", "饰品", "首饰", "袜饰", "丝袜", "包配" -> "accessory";
            default -> "top";
        };
    }

    public static boolean isBagMeta(String name, String subCategory) {
        String nm = Objects.toString(name, "").trim().toLowerCase(Locale.ROOT);
        String sub = Objects.toString(subCategory, "").trim().toLowerCase(Locale.ROOT);
        String combined = nm + " " + sub;
        if (combined.contains("丝袜") || combined.contains("堆堆袜") || combined.contains("袜")
                || combined.contains("项链") || combined.contains("耳环") || combined.contains("耳钉")
                || combined.contains("耳饰") || combined.contains("手镯") || combined.contains("手链")
                || combined.contains("戒指") || combined.contains("腕表") || combined.contains("手表")
                || combined.contains("墨镜") || combined.contains("太阳镜") || combined.contains("围巾")
                || combined.contains("丝巾") || combined.contains("帽") || combined.contains("腰带")
                || combined.contains("包臀") || combined.contains("面包服")) {
            return false;
        }
        if (nm.endsWith("包") || sub.endsWith("包")) {
            return true;
        }
        return combined.contains("包袋") || combined.contains("托特") || combined.contains("腋下")
                || combined.contains("手提包") || combined.contains("单肩包") || combined.contains("斜挎包")
                || combined.contains("链条包") || combined.contains("水桶包") || combined.contains("马鞍包")
                || combined.contains("双肩包") || combined.contains("玩偶包") || combined.contains("流浪包")
                || combined.contains("翻盖包") || combined.contains("手袋") || combined.contains("钱包")
                || combined.contains("背包") || combined.contains("卡包") || combined.contains("手拿包")
                || combined.contains("tote") || combined.contains("bag");
    }

    public String refineCategory(String rawCategory, String subCategory, String name, String styleTags) {
        String norm = normalizeCategory(rawCategory);
        if ("accessory".equals(norm) && isBagMeta(name, subCategory)) {
            return "bag";
        }
        if ("top".equals(norm) && OutfitRecommendationService.isOnePieceMeta(norm, subCategory, name, styleTags)) {
            return "dress";
        }
        return norm;
    }

    public String inferSvgType(String category, String name) {
        String n = name != null ? name : "";
        if (n.contains("风衣")) return "trench";
        if (n.contains("西装") && !"bottom".equals(category)) return "blazer";
        if (n.contains("麂皮") || n.contains("夹克")) return "suede_jacket";
        if (n.contains("开衫")) return "cardigan";
        if (n.contains("条纹") || n.contains("海魂")) return "striped_shirt";
        if (n.contains("背心") || n.contains("马甲")) return "knit_vest";
        if (n.contains("牛仔")) return "jeans";
        if (n.contains("裙")) return "pleated_skirt";
        if (n.contains("裤")) return "trousers";
        if (n.contains("乐福") || n.contains("皮鞋")) return "loafers";
        if (n.contains("德训") || n.contains("运动鞋") || n.contains("鞋")) return "sneakers";
        if (n.contains("托特")) return "tote_bag";
        if (n.contains("包")) return "crossbody_bag";
        return switch (category != null ? category : "top") {
            case "outerwear" -> "cardigan";
            case "dress" -> "pleated_skirt";
            case "bottom" -> "trousers";
            case "shoes" -> "loafers";
            case "bag", "accessory" -> "tote_bag";
            default -> "tshirt";
        };
    }

    private int inferThickness(List<String> seasons, String category) {
        if (seasons != null) {
            if (seasons.contains("冬") || seasons.contains("winter")) return "outerwear".equals(category) ? 3 : 2;
            if (seasons.size() == 1 && (seasons.contains("夏") || seasons.contains("summer"))) return 1;
        }
        return "outerwear".equals(category) ? 2 : 1;
    }
}
