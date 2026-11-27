package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.OutfitHistoryMapper;
import com.wardrobe.mapper.OutfitPlanMapper;
import com.wardrobe.mapper.UserPreferenceMapper;
import com.wardrobe.mapper.WardrobeItemMapper;
import com.wardrobe.model.dto.PreferenceDto;
import com.wardrobe.model.entity.OutfitHistory;
import com.wardrobe.model.entity.OutfitPlan;
import com.wardrobe.model.entity.UserPreference;
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
public class PreferenceService {
    private static final List<Map.Entry<String, String>> CATEGORY_META = List.of(
            Map.entry("outerwear", "外套"),
            Map.entry("top", "上衣"),
            Map.entry("dress", "连衣裙"),
            Map.entry("bottom", "下装"),
            Map.entry("shoes", "鞋履"),
            Map.entry("bag", "包袋"),
            Map.entry("accessory", "配饰")
    );

    private static final Map<String, List<String>> SEASON_PALETTES = Map.of(
            "春季暖亮", List.of("米白", "奶白", "浅杏", "奶黄", "牛仔蓝", "燕麦"),
            "夏季冷柔", List.of("纯白", "浅灰", "雾霾蓝", "淡粉", "牛仔蓝", "银灰"),
            "秋季暖深", List.of("燕麦", "焦糖棕", "卡其", "驼色", "深棕", "橄榄绿", "米白"),
            "冬季冷艳", List.of("炭黑", "黑色", "纯白", "深蓝", "酒红", "红色", "炭灰"),
            "中性百搭", List.of("米白", "燕麦", "纯白", "炭黑", "黑色", "牛仔蓝", "卡其")
    );

    private static final Map<String, String> SEASON_ADVICE = Map.of(
            "春季暖亮", "适合明快暖调与轻盈对比（米白、浅杏、水洗蓝），避开大面积沉闷黑灰",
            "夏季冷柔", "适合低饱和冷柔莫兰迪与柔和顺色（纯白、雾霾蓝、浅灰），避免过重焦糖色",
            "秋季暖深", "适合浓郁大地美拉德色系（燕麦、焦糖棕、卡其、橄榄绿），突出温润层次",
            "冬季冷艳", "适合高对比纯色与利落黑白（炭黑、纯白、深蓝、正红），打造冷感高级气场",
            "中性百搭", "冷暖皆宜，以黑白灰+燕麦米白+单宁蓝构建零失误的极简胶囊色板"
    );

    private static final Map<String, String> BODY_SHAPE_ADVICE = Map.of(
            "A型梨形", "上紧下松优化比例：优先短款上衣/亮色领口+高腰A字裙或垂感阔腿裤，弱化胯宽",
            "H型直筒", "重塑腰线与层次感：善用短款开衫、收腰西装、扎衣角与叠穿塑造立体曲线",
            "X型沙漏", "发挥天生腰臀比优势：优选修身针织、收腰剪裁与利落直筒裤，避免过于肥大廓形",
            "Y型倒三角", "平衡肩宽与下身量感：上装简洁V领/垂感深色，下装搭配阔腿西裤或百褶裙",
            "O型苹果", "纵向拉长利落显瘦：优选硬挺直筒风衣/西装外套敞开穿，搭配修身下装与轻盈鞋履"
    );

    private static final Map<String, List<String>> BODY_SHAPE_KEYWORDS = Map.of(
            "A型梨形", List.of("短款", "高腰", "阔腿", "A字", "直筒", "开衫"),
            "H型直筒", List.of("叠穿", "西装", "风衣", "收腰", "百褶", "马甲"),
            "X型沙漏", List.of("修身", "针织", "收腰", "吊带", "连衣裙", "直筒"),
            "Y型倒三角", List.of("阔腿", "百褶", "半身裙", "垂感", "V领", "直筒"),
            "O型苹果", List.of("风衣", "西装", "V领", "廓形", "垂感", "直筒")
    );

    private final UserPreferenceMapper preferenceMapper;
    private final WardrobeItemMapper wardrobeItemMapper;
    private final OutfitHistoryMapper outfitHistoryMapper;
    private final OutfitPlanMapper outfitPlanMapper;
    private final JsonUtils jsonUtils;

    public static List<String> getSeasonRecommendedColors(String colorSeason) {
        if (colorSeason == null || colorSeason.isBlank()) {
            return SEASON_PALETTES.get("中性百搭");
        }
        return SEASON_PALETTES.getOrDefault(colorSeason.trim(), SEASON_PALETTES.get("中性百搭"));
    }

    public static String getSeasonAdvice(String colorSeason) {
        if (colorSeason == null || colorSeason.isBlank()) {
            return SEASON_ADVICE.get("中性百搭");
        }
        return SEASON_ADVICE.getOrDefault(colorSeason.trim(), SEASON_ADVICE.get("中性百搭"));
    }

    public static String getBodyShapeAdvice(String bodyShape) {
        if (bodyShape == null || bodyShape.isBlank()) {
            return BODY_SHAPE_ADVICE.get("H型直筒");
        }
        return BODY_SHAPE_ADVICE.getOrDefault(bodyShape.trim(), BODY_SHAPE_ADVICE.get("H型直筒"));
    }

    public static List<String> getBodyShapeKeywords(String bodyShape) {
        if (bodyShape == null || bodyShape.isBlank()) {
            return BODY_SHAPE_KEYWORDS.get("H型直筒");
        }
        return BODY_SHAPE_KEYWORDS.getOrDefault(bodyShape.trim(), BODY_SHAPE_KEYWORDS.get("H型直筒"));
    }

    public UserPreference getOrCreateEntity(Long userId) {
        UserPreference pref = preferenceMapper.selectOne(
                new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        if (pref == null) {
            pref = new UserPreference();
            pref.setUserId(userId);
            pref.setDefaultCity("武汉");
            pref.setFavoriteStyles(jsonUtils.toJson(List.of("极简通勤", "Clean Fit")));
            pref.setFavoriteColors(jsonUtils.toJson(List.of("米白", "燕麦", "牛仔蓝")));
            pref.setDislikedColors(jsonUtils.toJson(List.of("荧光绿")));
            pref.setPreferredScenes(jsonUtils.toJson(List.of("职场通勤", "日常休闲")));
            pref.setBudgetLimit(new BigDecimal("300.00"));
            pref.setBodyShape("H型直筒");
            pref.setColorSeason("中性百搭");
            pref.setHeightCm(165);
            pref.setStyleWeights(jsonUtils.toJson(Map.of("极简通勤", 0.85, "Clean Fit", 0.78)));
            pref.setUpdatedAt(LocalDateTime.now());
            preferenceMapper.insert(pref);
        } else {
            boolean dirty = false;
            if (pref.getBodyShape() == null || pref.getBodyShape().isBlank()) {
                pref.setBodyShape("H型直筒");
                dirty = true;
            }
            if (pref.getColorSeason() == null || pref.getColorSeason().isBlank()) {
                pref.setColorSeason("中性百搭");
                dirty = true;
            }
            if (pref.getHeightCm() == null || pref.getHeightCm() <= 0) {
                pref.setHeightCm(165);
                dirty = true;
            }
            if (dirty) {
                preferenceMapper.updateById(pref);
            }
        }
        return pref;
    }

    public PreferenceDto.PreferenceVo getPreferenceWithAnalytics(Long userId) {
        UserPreference pref = getOrCreateEntity(userId);
        List<WardrobeItem> items = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));

        List<String> favStyles = jsonUtils.toStringList(pref.getFavoriteStyles());
        List<String> favColors = jsonUtils.toStringList(pref.getFavoriteColors());
        List<String> dislikedColors = jsonUtils.toStringList(pref.getDislikedColors());
        List<String> scenes = jsonUtils.toStringList(pref.getPreferredScenes());
        Map<String, Double> weights = jsonUtils.toDoubleMap(pref.getStyleWeights());

        int total = Math.max(items.size(), 1);
        List<PreferenceDto.CategoryStatItem> catStats = CATEGORY_META.stream()
                .map(entry -> {
                    long count = items.stream().filter(i -> entry.getKey().equals(i.getCategory())).count();
                    return PreferenceDto.CategoryStatItem.builder()
                            .key(entry.getKey())
                            .label(entry.getValue())
                            .count(count)
                            .percent((int) Math.round((count * 100.0) / total))
                            .build();
                })
                .filter(c -> c.getCount() > 0)
                .collect(Collectors.toList());

        Map<String, Long> tagCounts = new LinkedHashMap<>();
        for (WardrobeItem it : items) {
            for (String tag : jsonUtils.toStringList(it.getStyleTags())) {
                tagCounts.merge(tag, 1L, Long::sum);
            }
        }
        long maxTag = tagCounts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        List<PreferenceDto.StyleAffinityItem> styleAffinity = tagCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> {
                    int base = (int) Math.round((e.getValue() * 82.0) / maxTag);
                    int bonus = favStyles.contains(e.getKey()) ? 12 : 0;
                    int weightBonus = (int) Math.round(weights.getOrDefault(e.getKey(), 0.0) * 6);
                    return PreferenceDto.StyleAffinityItem.builder()
                            .name(e.getKey())
                            .score(Math.min(98, base + bonus + weightBonus))
                            .build();
                })
                .collect(Collectors.toList());

        Map<String, ColorBucket> colorMap = new LinkedHashMap<>();
        for (WardrobeItem it : items) {
            String color = it.getColor() != null ? it.getColor() : "其他";
            colorMap.computeIfAbsent(color, k -> new ColorBucket(color, it.getColorHex())).count++;
        }
        List<PreferenceDto.ColorStatItem> colorStats = colorMap.values().stream()
                .sorted((a, b) -> Long.compare(b.count, a.count))
                .map(b -> PreferenceDto.ColorStatItem.builder()
                        .color(b.color)
                        .hex(b.hex != null ? b.hex : "#EFECE6")
                        .count(b.count)
                        .percent((int) Math.round((b.count * 100.0) / total))
                        .build())
                .collect(Collectors.toList());

        int idleRate = items.isEmpty() ? 0 : (int) Math.round(
                items.stream().filter(i -> (i.getWearCount() == null ? 0 : i.getWearCount()) <= 3).count() * 100.0 / items.size()
        );

        BigDecimal totalValue = BigDecimal.ZERO;
        int totalPricedWears = 0;
        int pricedCount = 0;
        String bestName = null;
        BigDecimal bestCpw = null;
        String highestName = null;
        BigDecimal highestCpw = null;

        for (WardrobeItem it : items) {
            if (it.getPrice() != null && it.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                pricedCount++;
                totalValue = totalValue.add(it.getPrice());
                int wears = it.getWearCount() != null ? it.getWearCount() : 0;
                totalPricedWears += wears;
                BigDecimal cpw = wears > 0
                        ? it.getPrice().divide(BigDecimal.valueOf(wears), 2, RoundingMode.HALF_UP)
                        : it.getPrice().setScale(2, RoundingMode.HALF_UP);
                if (wears > 0 && (bestCpw == null || cpw.compareTo(bestCpw) < 0)) {
                    bestCpw = cpw;
                    bestName = it.getName();
                }
                if (highestCpw == null || cpw.compareTo(highestCpw) > 0) {
                    highestCpw = cpw;
                    highestName = it.getName();
                }
            }
        }
        BigDecimal avgCpw = null;
        if (pricedCount > 0) {
            avgCpw = totalPricedWears > 0
                    ? totalValue.divide(BigDecimal.valueOf(totalPricedWears), 2, RoundingMode.HALF_UP)
                    : totalValue.divide(BigDecimal.valueOf(pricedCount), 2, RoundingMode.HALF_UP);
        }

        // Style Mirror ("The Mirror" inspired by tandpfun/wardrobe):
        // 1. Core Staples (most worn)
        List<PreferenceDto.MirrorPieceItem> coreStaples = items.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getWearCount() != null ? b.getWearCount() : 0,
                        a.getWearCount() != null ? a.getWearCount() : 0))
                .limit(3)
                .map(this::toMirrorPiece)
                .collect(Collectors.toList());

        // 2. Neglected Pieces (least worn, prioritizing higher price for CPW revival)
        List<PreferenceDto.MirrorPieceItem> neglectedPieces = items.stream()
                .sorted(Comparator
                        .comparingInt((WardrobeItem w) -> w.getWearCount() != null ? w.getWearCount() : 0)
                        .thenComparing((WardrobeItem w) -> w.getPrice() != null ? w.getPrice() : BigDecimal.ZERO,
                                Comparator.reverseOrder()))
                .limit(3)
                .map(this::toMirrorPiece)
                .collect(Collectors.toList());

        // 3. Color-Pairing Fingerprints from actual outfit history + favorited plans
        List<PreferenceDto.ColorPairItem> colorPairs = computeColorPairFingerprints(userId, items);

        String bodyShape = pref.getBodyShape() != null ? pref.getBodyShape() : "H型直筒";
        String colorSeason = pref.getColorSeason() != null ? pref.getColorSeason() : "中性百搭";

        return PreferenceDto.PreferenceVo.builder()
                .defaultCity(pref.getDefaultCity())
                .favoriteStyles(favStyles)
                .favoriteColors(favColors)
                .dislikedColors(dislikedColors)
                .preferredScenes(scenes)
                .budgetLimit(pref.getBudgetLimit())
                .bodyShape(bodyShape)
                .colorSeason(colorSeason)
                .heightCm(pref.getHeightCm() != null ? pref.getHeightCm() : 165)
                .bodyShapeAdvice(getBodyShapeAdvice(bodyShape))
                .colorSeasonAdvice(getSeasonAdvice(colorSeason))
                .seasonRecommendedColors(getSeasonRecommendedColors(colorSeason))
                .styleWeights(weights)
                .categoryStats(catStats)
                .styleAffinity(styleAffinity)
                .colorStats(colorStats)
                .colorPairFingerprints(colorPairs)
                .coreStaples(coreStaples)
                .neglectedPieces(neglectedPieces)
                .idleRate(idleRate)
                .totalWardrobeValue(totalValue.setScale(2, RoundingMode.HALF_UP))
                .avgCostPerWear(avgCpw)
                .pricedItemCount(pricedCount)
                .bestValueItemName(bestName)
                .bestValueCpw(bestCpw)
                .highestCpwItemName(highestName)
                .highestCpw(highestCpw)
                .build();
    }

    private PreferenceDto.MirrorPieceItem toMirrorPiece(WardrobeItem it) {
        int wears = it.getWearCount() != null ? it.getWearCount() : 0;
        BigDecimal cpw = null;
        if (it.getPrice() != null && it.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            cpw = wears > 0
                    ? it.getPrice().divide(BigDecimal.valueOf(wears), 2, RoundingMode.HALF_UP)
                    : it.getPrice().setScale(2, RoundingMode.HALF_UP);
        }
        return PreferenceDto.MirrorPieceItem.builder()
                .id(it.getId())
                .name(it.getName())
                .category(it.getCategory())
                .color(it.getColor())
                .colorHex(it.getColorHex() != null ? it.getColorHex() : "#EFECE6")
                .wearCount(wears)
                .price(it.getPrice())
                .costPerWear(cpw)
                .imageUrl(it.getProcessedImageUrl())
                .svgType(it.getSvgType())
                .build();
    }

    private List<PreferenceDto.ColorPairItem> computeColorPairFingerprints(Long userId, List<WardrobeItem> items) {
        Map<Long, WardrobeItem> itemMap = items.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));
        List<List<Long>> outfitItemSets = new ArrayList<>();

        List<OutfitHistory> histories = outfitHistoryMapper.selectList(
                new LambdaQueryWrapper<OutfitHistory>().eq(OutfitHistory::getUserId, userId));
        for (OutfitHistory h : histories) {
            outfitItemSets.add(jsonUtils.toLongList(h.getItemIds()));
        }

        List<OutfitPlan> favPlans = outfitPlanMapper.selectList(
                new LambdaQueryWrapper<OutfitPlan>()
                        .eq(OutfitPlan::getUserId, userId)
                        .eq(OutfitPlan::getIsFavorite, 1));
        for (OutfitPlan p : favPlans) {
            outfitItemSets.add(jsonUtils.toLongList(p.getItemIds()));
        }

        Map<String, ColorPairBucket> pairCounts = new LinkedHashMap<>();
        for (List<Long> ids : outfitItemSets) {
            List<WardrobeItem> resolved = ids.stream()
                    .map(itemMap::get)
                    .filter(Objects::nonNull)
                    .filter(i -> i.getColor() != null && !i.getColor().isBlank())
                    .collect(Collectors.toList());
            Set<String> seenInOutfit = new HashSet<>();
            for (int i = 0; i < resolved.size(); i++) {
                for (int j = i + 1; j < resolved.size(); j++) {
                    String c1 = resolved.get(i).getColor();
                    String h1 = resolved.get(i).getColorHex();
                    String c2 = resolved.get(j).getColor();
                    String h2 = resolved.get(j).getColorHex();
                    if (c1.equals(c2)) continue;
                    String key = c1.compareTo(c2) <= 0 ? c1 + "|" + c2 : c2 + "|" + c1;
                    if (!seenInOutfit.add(key)) continue;
                    ColorPairBucket bucket = pairCounts.computeIfAbsent(key, k ->
                            c1.compareTo(c2) <= 0
                                    ? new ColorPairBucket(c1, h1, c2, h2)
                                    : new ColorPairBucket(c2, h2, c1, h1));
                    bucket.count++;
                }
            }
        }

        return pairCounts.values().stream()
                .sorted((a, b) -> Integer.compare(b.count, a.count))
                .limit(4)
                .map(b -> PreferenceDto.ColorPairItem.builder()
                        .colorA(b.colorA)
                        .hexA(b.hexA != null ? b.hexA : "#EFECE6")
                        .colorB(b.colorB)
                        .hexB(b.hexB != null ? b.hexB : "#262626")
                        .count(b.count)
                        .build())
                .collect(Collectors.toList());
    }

    public PreferenceDto.PreferenceVo updatePreference(Long userId, PreferenceDto.UpdateRequest req) {
        UserPreference pref = getOrCreateEntity(userId);
        if (req.getDefaultCity() != null && !req.getDefaultCity().isBlank()) {
            pref.setDefaultCity(req.getDefaultCity().trim());
        }
        if (req.getFavoriteStyles() != null) {
            pref.setFavoriteStyles(jsonUtils.toJson(req.getFavoriteStyles()));
            Map<String, Double> weights = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getStyleWeights()));
            for (String s : req.getFavoriteStyles()) {
                weights.put(s, Math.min(1.0, Math.max(weights.getOrDefault(s, 0.75), 0.85)));
            }
            pref.setStyleWeights(jsonUtils.toJson(weights));
        }
        if (req.getFavoriteColors() != null) {
            pref.setFavoriteColors(jsonUtils.toJson(req.getFavoriteColors()));
        }
        if (req.getDislikedColors() != null) {
            pref.setDislikedColors(jsonUtils.toJson(req.getDislikedColors()));
        }
        if (req.getPreferredScenes() != null) {
            pref.setPreferredScenes(jsonUtils.toJson(req.getPreferredScenes()));
        }
        if (req.getBudgetLimit() != null && req.getBudgetLimit().compareTo(BigDecimal.ZERO) > 0) {
            pref.setBudgetLimit(req.getBudgetLimit());
        }
        if (req.getBodyShape() != null && !req.getBodyShape().isBlank()) {
            pref.setBodyShape(req.getBodyShape().trim());
        }
        if (req.getColorSeason() != null && !req.getColorSeason().isBlank()) {
            pref.setColorSeason(req.getColorSeason().trim());
        }
        if (req.getHeightCm() != null && req.getHeightCm() >= 130 && req.getHeightCm() <= 220) {
            pref.setHeightCm(req.getHeightCm());
        }
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.updateById(pref);
        return getPreferenceWithAnalytics(userId);
    }

    /**
     * Implicit preference evolution: reinforce or decay style_weights from favorites, dislikes, or wear ratings.
     */
    public void reinforceStyleWeights(Long userId, List<WardrobeItem> items, double delta) {
        if (items == null || items.isEmpty()) return;
        List<String> tags = new ArrayList<>();
        for (WardrobeItem item : items) {
            tags.addAll(jsonUtils.toStringList(item.getStyleTags()));
        }
        reinforceStyleTags(userId, tags, delta);
    }

    public void reinforceStyleTags(Long userId, List<String> tags, double delta) {
        if (tags == null || tags.isEmpty()) return;
        UserPreference pref = getOrCreateEntity(userId);
        Map<String, Double> weights = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getStyleWeights()));
        applyStyleDelta(weights, tags, delta);
        pref.setStyleWeights(jsonUtils.toJson(weights));
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.updateById(pref);
    }

    /**
     * Records multi-signal outfit feedback across style weights, learned color affinity, and pairwise garment scores.
     */
    public void recordOutfitFeedback(Long userId,
                                     List<WardrobeItem> positiveItems,
                                     List<WardrobeItem> negativeItems,
                                     double styleDelta,
                                     double colorDelta,
                                     double pairDelta) {
        if ((positiveItems == null || positiveItems.isEmpty()) && (negativeItems == null || negativeItems.isEmpty())) {
            return;
        }
        UserPreference pref = getOrCreateEntity(userId);
        Map<String, Double> weights = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getStyleWeights()));
        Map<String, Double> colorScores = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getLearnedColorScores()));
        Map<String, Double> pairScores = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getLearnedPairScores()));

        if (positiveItems != null && !positiveItems.isEmpty()) {
            applyItemsFeedback(positiveItems, weights, colorScores, pairScores,
                    Math.abs(styleDelta), Math.abs(colorDelta), Math.abs(pairDelta));
        }
        if (negativeItems != null && !negativeItems.isEmpty()) {
            applyItemsFeedback(negativeItems, weights, colorScores, pairScores,
                    -Math.abs(styleDelta), -Math.abs(colorDelta), -Math.abs(pairDelta));
        }

        persistLearnedPreferences(pref, weights, colorScores, pairScores);
    }

    /**
     * Learns targeted preference shifts when a user swaps out one slot item in favor of another within an outfit.
     */
    public void recordSlotSwapFeedback(Long userId,
                                       WardrobeItem rejectedItem,
                                       WardrobeItem chosenItem,
                                       List<WardrobeItem> keptContextItems) {
        if (rejectedItem == null && chosenItem == null) {
            return;
        }
        UserPreference pref = getOrCreateEntity(userId);
        Map<String, Double> weights = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getStyleWeights()));
        Map<String, Double> colorScores = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getLearnedColorScores()));
        Map<String, Double> pairScores = new LinkedHashMap<>(jsonUtils.toDoubleMap(pref.getLearnedPairScores()));

        if (rejectedItem != null) {
            applyStyleDelta(weights, jsonUtils.toStringList(rejectedItem.getStyleTags()), -0.02);
            applyColorDelta(colorScores, rejectedItem.getColor(), -0.03);
            if (keptContextItems != null && rejectedItem.getId() != null) {
                for (WardrobeItem ctx : keptContextItems) {
                    if (ctx != null && ctx.getId() != null && !Objects.equals(ctx.getId(), rejectedItem.getId())) {
                        applyPairDelta(pairScores, rejectedItem.getId(), ctx.getId(), -0.05);
                    }
                }
            }
        }
        if (chosenItem != null) {
            applyStyleDelta(weights, jsonUtils.toStringList(chosenItem.getStyleTags()), 0.03);
            applyColorDelta(colorScores, chosenItem.getColor(), 0.04);
            if (keptContextItems != null && chosenItem.getId() != null) {
                for (WardrobeItem ctx : keptContextItems) {
                    if (ctx != null && ctx.getId() != null && !Objects.equals(ctx.getId(), chosenItem.getId())) {
                        applyPairDelta(pairScores, chosenItem.getId(), ctx.getId(), 0.06);
                    }
                }
            }
        }

        persistLearnedPreferences(pref, weights, colorScores, pairScores);
    }

    private void applyItemsFeedback(List<WardrobeItem> items,
                                    Map<String, Double> weights,
                                    Map<String, Double> colorScores,
                                    Map<String, Double> pairScores,
                                    double styleDelta,
                                    double colorDelta,
                                    double pairDelta) {
        for (WardrobeItem item : items) {
            if (item == null) continue;
            applyStyleDelta(weights, jsonUtils.toStringList(item.getStyleTags()), styleDelta);
            applyColorDelta(colorScores, item.getColor(), colorDelta);
        }
        if (Math.abs(pairDelta) > 1e-6 && items.size() >= 2) {
            for (int i = 0; i < items.size(); i++) {
                WardrobeItem a = items.get(i);
                if (a == null || a.getId() == null) continue;
                for (int j = i + 1; j < items.size(); j++) {
                    WardrobeItem b = items.get(j);
                    if (b == null || b.getId() == null || Objects.equals(a.getId(), b.getId())) continue;
                    applyPairDelta(pairScores, a.getId(), b.getId(), pairDelta);
                }
            }
        }
    }

    private void applyStyleDelta(Map<String, Double> weights, List<String> tags, double delta) {
        if (tags == null || tags.isEmpty() || Math.abs(delta) < 1e-6) return;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            double current = weights.getOrDefault(tag, 0.55);
            double next = Math.max(0.10, Math.min(1.0, Math.round((current + delta) * 100.0) / 100.0));
            weights.put(tag, next);
        }
    }

    private void applyColorDelta(Map<String, Double> colorScores, String color, double delta) {
        if (color == null || color.isBlank() || Math.abs(delta) < 1e-6) return;
        String key = color.trim();
        double current = colorScores.getOrDefault(key, 0.0);
        double next = Math.max(-0.50, Math.min(0.50, Math.round((current + delta) * 100.0) / 100.0));
        colorScores.put(key, next);
    }

    private void applyPairDelta(Map<String, Double> pairScores, Long idA, Long idB, double delta) {
        String key = pairKey(idA, idB);
        if (key == null) return;
        double current = pairScores.getOrDefault(key, 0.0);
        double next = Math.max(-0.40, Math.min(0.40, Math.round((current + delta) * 100.0) / 100.0));
        pairScores.put(key, next);
    }

    private void persistLearnedPreferences(UserPreference pref,
                                           Map<String, Double> weights,
                                           Map<String, Double> colorScores,
                                           Map<String, Double> pairScores) {
        Map<String, Double> boundedPairs = pairScores.entrySet().stream()
                .filter(e -> e.getValue() != null && Math.abs(e.getValue()) >= 0.005)
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(120)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        pref.setStyleWeights(jsonUtils.toJson(weights));
        pref.setLearnedColorScores(jsonUtils.toJson(colorScores));
        pref.setLearnedPairScores(jsonUtils.toJson(boundedPairs));
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.updateById(pref);
    }

    public static String pairKey(Long idA, Long idB) {
        if (idA == null || idB == null || Objects.equals(idA, idB)) return null;
        return Math.min(idA, idB) + "_" + Math.max(idA, idB);
    }

    private static class ColorBucket {
        final String color;
        final String hex;
        long count = 0;

        ColorBucket(String color, String hex) {
            this.color = color;
            this.hex = hex;
        }
    }

    private static class ColorPairBucket {
        final String colorA;
        final String hexA;
        final String colorB;
        final String hexB;
        int count = 0;

        ColorPairBucket(String colorA, String hexA, String colorB, String hexB) {
            this.colorA = colorA;
            this.hexA = hexA;
            this.colorB = colorB;
            this.hexB = hexB;
        }
    }
}
