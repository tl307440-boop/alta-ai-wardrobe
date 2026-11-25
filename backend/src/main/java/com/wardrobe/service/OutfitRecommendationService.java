package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.OutfitHistoryMapper;
import com.wardrobe.mapper.OutfitPlanMapper;
import com.wardrobe.mapper.WardrobeItemMapper;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.model.dto.WardrobeDto;
import com.wardrobe.model.dto.WeatherVo;
import com.wardrobe.model.entity.OutfitHistory;
import com.wardrobe.model.entity.OutfitPlan;
import com.wardrobe.model.entity.UserPreference;
import com.wardrobe.model.entity.WardrobeItem;
import com.wardrobe.service.client.MultimodalLlmClient;
import com.wardrobe.service.client.WeatherApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutfitRecommendationService {
    private static final List<String> SLOT_ORDER = List.of("outerwear", "top", "bottom", "shoes", "accessory");
    private static final Map<String, String> SLOT_LABELS = Map.of(
            "outerwear", "外套",
            "top", "上衣",
            "bottom", "下装",
            "shoes", "鞋履",
            "accessory", "包配"
    );

    private final WardrobeItemMapper wardrobeItemMapper;
    private final OutfitPlanMapper outfitPlanMapper;
    private final OutfitHistoryMapper outfitHistoryMapper;
    private final PreferenceService preferenceService;
    private final WardrobeService wardrobeService;
    private final CommerceMatchService commerceMatchService;
    private final WeatherApiClient weatherApiClient;
    private final AiConfigService aiConfigService;
    private final MultimodalLlmClient llmClient;
    private final StorageService storageService;
    private final JsonUtils jsonUtils;

    public OutfitDto.PlanVo recommend(Long userId, OutfitDto.RecommendRequest req) {
        UserPreference pref = preferenceService.getOrCreateEntity(userId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        BigDecimal budget = req.getBudgetLimit() != null ? req.getBudgetLimit() : pref.getBudgetLimit();

        WeatherVo weather = resolveWeather(pref, req);
        String triggerType = req.getTriggerType() != null && !req.getTriggerType().isBlank()
                ? req.getTriggerType() : "daily_weather";
        String scene = resolveScene(triggerType, req, weather);

        // Fetch recent 7-day history for diversity penalty (PRD §4.3)
        LocalDate sevenDaysAgo = LocalDate.now().minusDays(7);
        List<OutfitHistory> recentHistory = outfitHistoryMapper.selectList(
                new LambdaQueryWrapper<OutfitHistory>()
                        .eq(OutfitHistory::getUserId, userId)
                        .ge(OutfitHistory::getWearDate, sevenDaysAgo));
        Map<Long, Integer> recentWearCounts = new HashMap<>();
        for (OutfitHistory h : recentHistory) {
            for (Long id : jsonUtils.toLongList(h.getItemIds())) {
                recentWearCounts.merge(id, 1, Integer::sum);
            }
        }

        // Stage 1 & 2: Hard constraint filtering + weighted personalized scoring formula S(i)
        boolean reviveIdle = Boolean.TRUE.equals(req.getReviveIdle());
        Set<Long> excludeSet = req.getExcludeItemIds() != null
                ? new HashSet<>(req.getExcludeItemIds()) : Collections.emptySet();
        List<ScoredItem> scored = scoreWardrobeItems(wardrobe, pref, weather.getTemp(), scene, recentWearCounts, reviveIdle, excludeSet);

        // Handle multi-turn refinement on an existing plan
        OutfitPlan basePlan = null;
        if (req.getBasePlanId() != null) {
            OutfitPlan found = outfitPlanMapper.selectById(req.getBasePlanId());
            if (found != null && Objects.equals(found.getUserId(), userId)) {
                basePlan = found;
            }
        }

        WardrobeItem anchor = null;
        if (req.getAnchorItemId() != null) {
            anchor = wardrobe.stream()
                    .filter(w -> Objects.equals(w.getId(), req.getAnchorItemId()))
                    .findFirst().orElse(null);
        }

        // Stage 3: Real LLM stylist ranking if user has active API key, else deterministic stylist
        AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
        StylistOutput output = null;
        boolean usedRealLlm = false;

        if (creds.active() && !wardrobe.isEmpty()) {
            try {
                output = callLlmStylist(creds, scored, weather, scene, req, anchor, basePlan, pref, budget);
                usedRealLlm = (output != null);
            } catch (Exception e) {
                log.warn("LLM stylist failed, degrading gracefully to weighted deterministic engine: {}", e.getMessage());
            }
        }

        if (output == null) {
            output = deterministicStylist(wardrobe, scored, weather, triggerType, scene, req, anchor, basePlan, budget);
        }

        // Stage 4: Budget-filtered commerce match for missing items
        List<OutfitDto.ProductVo> recommendedProducts = new ArrayList<>();
        Set<Long> seenProductIds = new LinkedHashSet<>();
        for (OutfitDto.MissingSlotVo m : output.missingItems) {
            List<OutfitDto.ProductVo> found = commerceMatchService.matchProducts(
                    m.getCategory(), m.getKeywords(), m.getColor(), budget, 3);
            for (OutfitDto.ProductVo p : found) {
                if (seenProductIds.add(p.getId())) {
                    recommendedProducts.add(p);
                }
            }
        }
        if (recommendedProducts.isEmpty()) {
            recommendedProducts.addAll(commerceMatchService.matchProducts(null, scene, null, budget, 3));
        }

        // Persist generated outfit plan
        OutfitPlan plan = new OutfitPlan();
        plan.setUserId(userId);
        plan.setThemeTitle(output.themeTitle);
        plan.setTriggerType(triggerType);
        plan.setSceneTag(scene);
        plan.setWeatherSnapshot(jsonUtils.toJson(weather));
        plan.setUserQuery(req.getRefineInstruction() != null && !req.getRefineInstruction().isBlank()
                ? req.getRefineInstruction() : req.getUserQuery());
        plan.setItemIds(jsonUtils.toJson(output.selectedItemIds));
        plan.setMissingItems(jsonUtils.toJson(output.missingItems));
        plan.setRecommendedProductIds(jsonUtils.toJson(
                recommendedProducts.stream().map(OutfitDto.ProductVo::getId).collect(Collectors.toList())));
        plan.setAiReasoning(output.aiReasoning);
        plan.setMatchScore(BigDecimal.valueOf(output.matchScore).setScale(1, RoundingMode.HALF_UP));
        plan.setIsFavorite(0);
        plan.setCreatedAt(LocalDateTime.now());
        outfitPlanMapper.insert(plan);

        return buildPlanVo(plan, wardrobe, anchor != null ? anchor.getId() : null, recommendedProducts, usedRealLlm);
    }

    /**
     * Exact PRD §4.3 weighted personalized scoring formula enhanced with Seasonal Color,
     * Body Shape silhouette matching, Revive Idle Mode, and Dislike exclusion:
     * S(i) = 0.30*TempMatch + 0.25*StylePref + 0.15*ColorPref + 0.30*SceneMatch - 0.15*RecentWearPenalty
     *        + SeasonColorBonus + BodyShapeBonus + IdleRevivalBonus - ExcludePenalty
     */
    public List<ScoredItem> scoreWardrobeItems(List<WardrobeItem> wardrobe, UserPreference pref, int temp,
                                               String scene, Map<Long, Integer> recentWearCounts) {
        return scoreWardrobeItems(wardrobe, pref, temp, scene, recentWearCounts, false, Collections.emptySet());
    }

    public List<ScoredItem> scoreWardrobeItems(List<WardrobeItem> wardrobe, UserPreference pref, int temp,
                                               String scene, Map<Long, Integer> recentWearCounts,
                                               boolean reviveIdle, Set<Long> excludeItemIds) {
        List<String> favStyles = jsonUtils.toStringList(pref.getFavoriteStyles());
        List<String> favColors = jsonUtils.toStringList(pref.getFavoriteColors());
        List<String> dislikedColors = jsonUtils.toStringList(pref.getDislikedColors());
        Map<String, Double> styleWeights = jsonUtils.toDoubleMap(pref.getStyleWeights());
        List<String> seasonPalette = PreferenceService.getSeasonRecommendedColors(pref.getColorSeason());
        List<String> shapeKeywords = PreferenceService.getBodyShapeKeywords(pref.getBodyShape());

        List<ScoredItem> result = new ArrayList<>();
        for (WardrobeItem item : wardrobe) {
            int thickness = item.getThickness() != null ? item.getThickness() : 2;
            String season = Objects.toString(item.getSeason(), "spring,autumn").toLowerCase();

            // Stage 1: Hard constraints
            if (temp >= 25 && thickness >= 3) continue;
            if (temp <= 10 && thickness == 1 && !season.contains("autumn") && !season.contains("winter")) continue;

            double tempMatch = computeTempMatch(temp, thickness, season);
            double stylePref = computeStylePref(item, favStyles, styleWeights);
            double colorPref = computeColorPref(item.getColor(), favColors, dislikedColors);
            double sceneMatch = computeSceneMatch(item, scene);
            int recentCount = recentWearCounts.getOrDefault(item.getId(), 0);
            double recentPenalty = Math.min(1.0, recentCount * 0.35);

            double total = 0.30 * tempMatch + 0.25 * stylePref + 0.15 * colorPref + 0.30 * sceneMatch - 0.15 * recentPenalty;
            if (dislikedColors.contains(item.getColor())) {
                total -= 0.40;
            }
            // Seasonal Color Analysis harmony bonus
            if (item.getColor() != null && seasonPalette.contains(item.getColor())) {
                total += 0.08;
            }
            // Body Shape flattering silhouette bonus
            String itemHay = Objects.toString(item.getName(), "") + " " + Objects.toString(item.getStyleTags(), "");
            if (shapeKeywords.stream().anyMatch(itemHay::contains)) {
                total += 0.06;
            }
            // Revive Idle Mode ("The Mirror" neglected piece booster)
            int wears = item.getWearCount() != null ? item.getWearCount() : 0;
            if (reviveIdle && wears <= 2) {
                total += 0.25;
            }
            // Dislike / avoid previously rejected items
            if (excludeItemIds != null && excludeItemIds.contains(item.getId())) {
                total -= 0.35;
            }
            result.add(new ScoredItem(item, Math.max(0.0, Math.min(1.0, total))));
        }

        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    private double computeTempMatch(int temp, int thickness, String season) {
        if (temp >= 25) {
            if (thickness == 1 || season.contains("summer")) return 1.0;
            return 0.45;
        } else if (temp <= 11) {
            if (thickness == 3 || season.contains("winter")) return 1.0;
            if (thickness == 2) return 0.78;
            return 0.35;
        } else {
            if (thickness == 2 || season.contains("spring") || season.contains("autumn")) return 1.0;
            return 0.72;
        }
    }

    private double computeStylePref(WardrobeItem item, List<String> favStyles, Map<String, Double> styleWeights) {
        List<String> tags = jsonUtils.toStringList(item.getStyleTags());
        if (tags.isEmpty()) return 0.5;
        double best = 0.45;
        for (String tag : tags) {
            double w = styleWeights.getOrDefault(tag, favStyles.contains(tag) ? 0.85 : 0.55);
            if (favStyles.contains(tag)) w = Math.min(1.0, w + 0.10);
            if (w > best) best = w;
        }
        return best;
    }

    private double computeColorPref(String color, List<String> favColors, List<String> dislikedColors) {
        if (color == null) return 0.5;
        if (dislikedColors.contains(color)) return 0.0;
        if (favColors.contains(color)) return 1.0;
        return 0.65;
    }

    private double computeSceneMatch(WardrobeItem item, String scene) {
        String s = Objects.toString(scene, "");
        String hay = (Objects.toString(item.getName(), "") + " " + Objects.toString(item.getStyleTags(), "")).toLowerCase();
        if (s.contains("面试") || s.contains("职场")) {
            if (hay.contains("西装") || hay.contains("面试") || hay.contains("通勤") || hay.contains("乐福") || hay.contains("托特")) return 1.0;
            return 0.65;
        }
        if (s.contains("约会") || s.contains("温柔")) {
            if (hay.contains("温柔") || hay.contains("约会") || hay.contains("裙") || hay.contains("开衫") || hay.contains("马鞍")) return 1.0;
            return 0.65;
        }
        if (s.contains("咖啡") || s.contains("休闲")) {
            if (hay.contains("休闲") || hay.contains("clean") || hay.contains("牛仔") || hay.contains("德训") || hay.contains("美拉德")) return 1.0;
            return 0.70;
        }
        return 0.80;
    }

    private StylistOutput callLlmStylist(AiConfigService.ResolvedAiCredentials creds, List<ScoredItem> scored,
                                         WeatherVo weather, String scene, OutfitDto.RecommendRequest req,
                                         WardrobeItem anchor, OutfitPlan basePlan, UserPreference pref, BigDecimal budget) {
        List<Map<String, Object>> candidates = scored.stream().limit(25).map(si -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", si.item.getId());
            m.put("name", si.item.getName());
            m.put("category", si.item.getCategory());
            m.put("color", si.item.getColor());
            m.put("styleTags", jsonUtils.toStringList(si.item.getStyleTags()));
            m.put("score", Math.round(si.score * 100));
            return m;
        }).collect(Collectors.toList());

        StringBuilder prompt = new StringBuilder();
        prompt.append("天气：").append(weather.getCity()).append(" ").append(weather.getCondition())
                .append(" ").append(weather.getTemp()).append("℃（").append(weather.getTip()).append("）\n");
        prompt.append("场景：").append(scene).append("\n");
        prompt.append("用户偏好风格：").append(pref.getFavoriteStyles()).append("，避雷色系：").append(pref.getDislikedColors()).append("\n");
        prompt.append("用户身形与四季色彩：").append(Objects.toString(pref.getBodyShape(), "H型直筒"))
                .append("（身高 ").append(pref.getHeightCm() != null ? pref.getHeightCm() : 165).append("cm），")
                .append("色彩季型：").append(Objects.toString(pref.getColorSeason(), "中性百搭"))
                .append("（修饰建议：").append(PreferenceService.getBodyShapeAdvice(pref.getBodyShape())).append("）\n");
        if (Boolean.TRUE.equals(req.getReviveIdle())) {
            prompt.append("特别模式：已开启「盘活闲置模式」，请优先把穿戴次数少（wearCount<=2）的冷宫单品搭成高级感造型！\n");
        }
        prompt.append("预算上限：¥").append(budget.stripTrailingZeros().toPlainString()).append("\n");
        if (anchor != null) {
            prompt.append("必须包含的锁定锚点单品 ID=").append(anchor.getId()).append("（").append(anchor.getName()).append("）\n");
        }
        if (req.getUserQuery() != null && !req.getUserQuery().isBlank()) {
            prompt.append("用户自然语言诉求：").append(req.getUserQuery()).append("\n");
        }
        if (basePlan != null && req.getRefineInstruction() != null && !req.getRefineInstruction().isBlank()) {
            prompt.append("上一轮方案已选单品ID：").append(basePlan.getItemIds())
                    .append("，当前微调指令：").append(req.getRefineInstruction()).append("\n");
        }
        prompt.append("按个性化公式打分排序后的候选衣橱 JSON：\n").append(jsonUtils.toJson(candidates));

        JsonNode json = llmClient.curateOutfitPlan(creds, prompt.toString());
        Set<Long> validIds = scored.stream().map(s -> s.item.getId()).collect(Collectors.toSet());
        List<Long> selectedIds = new ArrayList<>();
        if (json.path("selectedItemIds").isArray()) {
            for (JsonNode idNode : json.path("selectedItemIds")) {
                long id = idNode.asLong(-1);
                if (validIds.contains(id) && !selectedIds.contains(id)) {
                    selectedIds.add(id);
                }
            }
        }
        if (anchor != null && !selectedIds.contains(anchor.getId())) {
            selectedIds.add(0, anchor.getId());
        }
        if (selectedIds.isEmpty()) return null;

        List<OutfitDto.MissingSlotVo> missing = new ArrayList<>();
        if (json.path("missingItems").isArray()) {
            for (JsonNode m : json.path("missingItems")) {
                String cat = wardrobeService.normalizeCategory(m.path("category").asText("outerwear"));
                String name = m.path("name").asText("建议补充单品");
                String color = m.path("color").asText("燕麦");
                missing.add(OutfitDto.MissingSlotVo.builder()
                        .category(cat)
                        .name(name)
                        .color(color)
                        .colorHex(m.path("colorHex").asText("#DFD3C3"))
                        .svgType(wardrobeService.inferSvgType(cat, name))
                        .keywords(m.path("keywords").asText(name + " " + color))
                        .reason(m.path("reason").asText("补全整套视觉层次"))
                        .build());
            }
        }
        String title = json.path("themeTitle").asText("AI 定制 · 杂志级穿搭");
        String reasoning = json.path("aiReasoning").asText("已结合实时天气与个人风格权重从衣橱中优先挑选高匹配单品完成协同搭配。");
        double score = Math.max(82.0, Math.min(99.0, json.path("matchScore").asDouble(95.0)));
        return new StylistOutput(title, selectedIds, missing, reasoning, score);
    }

    private StylistOutput deterministicStylist(List<WardrobeItem> wardrobe, List<ScoredItem> scored, WeatherVo weather,
                                               String triggerType, String scene, OutfitDto.RecommendRequest req,
                                               WardrobeItem anchor, OutfitPlan basePlan, BigDecimal budget) {
        Map<String, WardrobeItem> chosen = new LinkedHashMap<>();

        // If refining an existing plan, start from its items
        if (basePlan != null && req.getRefineInstruction() != null && !req.getRefineInstruction().isBlank()) {
            List<Long> baseIds = jsonUtils.toLongList(basePlan.getItemIds());
            for (Long id : baseIds) {
                wardrobe.stream().filter(w -> Objects.equals(w.getId(), id)).findFirst()
                        .ifPresent(it -> chosen.put(it.getCategory(), it));
            }
            String cmd = req.getRefineInstruction().trim();
            if (cmd.contains("黑") && (cmd.contains("裤") || cmd.contains("阔腿"))) {
                findMatch(wardrobe, "bottom", "黑", "阔腿").ifPresent(it -> chosen.put("bottom", it));
                return new StylistOutput(basePlan.getThemeTitle(),
                        orderedIds(chosen),
                        parseMissing(basePlan.getMissingItems()),
                        "已按微调指令将下装换为「" + chosen.get("bottom").getName() + "」，纵向线条更利落显瘦，通勤气场更足。",
                        96.0);
            }
            if (cmd.contains("休闲") || cmd.contains("鞋") || cmd.contains("德训")) {
                findMatch(wardrobe, "shoes", "德训", "休闲").ifPresent(it -> chosen.put("shoes", it));
                return new StylistOutput(basePlan.getThemeTitle(),
                        orderedIds(chosen),
                        parseMissing(basePlan.getMissingItems()),
                        "已将鞋履替换为「" + chosen.get("shoes").getName() + "」，削弱拘谨感，更适合日常步行与轻松出街。",
                        95.0);
            }
            if (cmd.contains("预算")) {
                return new StylistOutput(basePlan.getThemeTitle(),
                        orderedIds(chosen),
                        parseMissing(basePlan.getMissingItems()),
                        "已将缺品导购预算调整至 ¥" + budget.stripTrailingZeros().toPlainString() + " 以内，并重新召回高性价比平替商品。",
                        95.0);
            }
        }

        if ("anchor_item".equals(triggerType) && anchor != null) {
            chosen.put(anchor.getCategory(), anchor);
            for (String cat : SLOT_ORDER) {
                if (chosen.containsKey(cat)) continue;
                scored.stream().map(s -> s.item)
                        .filter(i -> cat.equals(i.getCategory()) && !Objects.equals(i.getId(), anchor.getId()))
                        .findFirst().ifPresent(it -> chosen.put(cat, it));
            }
            return new StylistOutput(
                    "一衣多搭 · 围绕「" + anchor.getName() + "」",
                    orderedIds(chosen),
                    Collections.emptyList(),
                    "已锁定「" + anchor.getName() + "」（" + anchor.getColor() + "）为核心视觉锚点，基于风格与色系和谐度自动补全其余高分衣橱单品。",
                    95.5
            );
        }

        String q = Objects.toString(req.getUserQuery(), "").trim();
        if ("nl_query".equals(triggerType) && !q.isBlank()) {
            if (q.contains("面试") || q.contains("职场") || q.contains("精神")) {
                pickBest(scored, wardrobe, chosen, "outerwear", "西装", "风衣");
                pickBest(scored, wardrobe, chosen, "top", "白T", "衬衫");
                pickBest(scored, wardrobe, chosen, "bottom", "西装", "阔腿");
                pickBest(scored, wardrobe, chosen, "shoes", "乐福", "皮鞋");
                pickBest(scored, wardrobe, chosen, "accessory", "托特", "通勤");
                List<OutfitDto.MissingSlotVo> missing = chosen.containsKey("outerwear") ? Collections.emptyList() : List.of(trenchMissing());
                return new StylistOutput("利落松弛 · 互联网面试", orderedIds(chosen), missing,
                        "针对互联网面试「精神但不死板」的诉求，选用柔和廓形外套内搭重磅白T与垂感阔腿裤，干练克制又具审美松弛感。", 97.0);
            }
            if (q.contains("约会") || q.contains("温柔")) {
                pickBest(scored, wardrobe, chosen, "outerwear", "开衫", "米白");
                pickBest(scored, wardrobe, chosen, "top", "白T", "纯白");
                pickBest(scored, wardrobe, chosen, "bottom", "裙", "卡其");
                pickBest(scored, wardrobe, chosen, "shoes", "乐福", "焦糖");
                pickBest(scored, wardrobe, chosen, "accessory", "马鞍", "腋下");
                List<OutfitDto.MissingSlotVo> missing = chosen.containsKey("outerwear") ? Collections.emptyList() : List.of(trenchMissing());
                return new StylistOutput("落日燕麦 · 约会温柔风", orderedIds(chosen), missing,
                        "以低饱和燕麦与卡其色系勾勒温柔轮廓，用焦糖色配件首尾呼应，自带柔和约会氛围滤镜。", 95.0);
            }
            // Cafe / casual retro
            pickBest(scored, wardrobe, chosen, "top", "白T", "条纹");
            pickBest(scored, wardrobe, chosen, "bottom", "牛仔", "阔腿");
            pickBest(scored, wardrobe, chosen, "shoes", "德训", "运动");
            pickBest(scored, wardrobe, chosen, "accessory", "马鞍", "托特");
            return new StylistOutput("周末咖啡馆 · 复古松弛", orderedIds(chosen), List.of(suedeMissing()),
                    "结合周末松弛复古氛围，用已有直筒牛仔与复古德训鞋打底，建议补充美拉德翻毛麂皮夹克点亮整体层次。", 94.5);
        }

        // Default daily_weather driven recommendation
        int temp = weather.getTemp();
        if (temp >= 25) {
            pickBest(scored, wardrobe, chosen, "top", "白T", "纯白");
            pickBest(scored, wardrobe, chosen, "bottom", "裙", "牛仔");
            pickBest(scored, wardrobe, chosen, "shoes", "德训", "乐福");
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包");
            List<OutfitDto.MissingSlotVo> missing = chosen.isEmpty() ? List.of(stripedTeeMissing()) : Collections.emptyList();
            return new StylistOutput("清透燕麦 · 晴暖出街", orderedIds(chosen), missing,
                    temp + "℃ 晴暖天气已自动过滤厚外套，优先挑选轻盈透气单品，延续低饱和燕麦画廊美学。", 94.0);
        } else if (temp <= 11) {
            pickBest(scored, wardrobe, chosen, "top", "背心", "针织");
            pickBest(scored, wardrobe, chosen, "bottom", "阔腿", "牛仔");
            pickBest(scored, wardrobe, chosen, "shoes", "乐福", "德训");
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包");
            return new StylistOutput("美拉德防风 · 降温保暖", orderedIds(chosen), List.of(suedeMissing()),
                    temp + "℃ 降温天气优先保障锁温与防风，内搭针织叠穿并建议补充美拉德麂皮工装外套增添暖调质感。", 95.0);
        } else {
            pickBest(scored, wardrobe, chosen, "top", "白T", "纯白");
            pickBest(scored, wardrobe, chosen, "bottom", "牛仔", "阔腿");
            pickBest(scored, wardrobe, chosen, "shoes", "乐福", "德训");
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包");
            return new StylistOutput("温柔通勤 · 轻松出门", orderedIds(chosen), List.of(trenchMissing()),
                    weather.getCity() + "当前 " + temp + "℃ " + weather.getCondition()
                            + "，以衣橱已有基础白T与牛仔裤构建利落基底，焦糖乐福鞋与燕麦托特包呼应大地色系；补齐短款风衣即可拿捏通勤松弛感。",
                    96.0);
        }
    }

    public OutfitDto.PlanVo swapSlot(Long userId, Long planId, OutfitDto.SwapSlotRequest req) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        String targetCat = wardrobeService.normalizeCategory(req.getCategory());
        List<WardrobeItem> sameCat = wardrobe.stream()
                .filter(w -> targetCat.equals(w.getCategory()))
                .collect(Collectors.toList());
        if (sameCat.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "当前衣橱中没有可替换的同品类单品");
        }

        List<Long> currentIds = new ArrayList<>(jsonUtils.toLongList(plan.getItemIds()));
        Set<Long> exclude = new HashSet<>(req.getExcludeItemIds() != null ? req.getExcludeItemIds() : Collections.emptyList());

        WardrobeItem currentInCat = currentIds.stream()
                .map(id -> wardrobe.stream().filter(w -> Objects.equals(w.getId(), id)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .filter(w -> targetCat.equals(w.getCategory()))
                .findFirst().orElse(null);

        WardrobeItem nextItem = null;
        if (currentInCat != null) {
            int idx = sameCat.indexOf(currentInCat);
            for (int step = 1; step <= sameCat.size(); step++) {
                WardrobeItem cand = sameCat.get((idx + step) % sameCat.size());
                if (!exclude.contains(cand.getId())) {
                    nextItem = cand;
                    break;
                }
            }
            if (nextItem == null) nextItem = sameCat.get((idx + 1) % sameCat.size());
            currentIds.remove(currentInCat.getId());
            currentIds.add(nextItem.getId());
        } else {
            nextItem = sameCat.get(0);
            currentIds.add(nextItem.getId());
        }

        // Remove missing item of same category since user swapped in an owned item
        List<OutfitDto.MissingSlotVo> missing = parseMissing(plan.getMissingItems()).stream()
                .filter(m -> !targetCat.equalsIgnoreCase(m.getCategory()))
                .collect(Collectors.toList());

        plan.setItemIds(jsonUtils.toJson(currentIds));
        plan.setMissingItems(jsonUtils.toJson(missing));
        plan.setAiReasoning("已将「" + SLOT_LABELS.getOrDefault(targetCat, "单品") + "」局部替换为衣橱已有单品「"
                + nextItem.getName() + "」（" + nextItem.getColor() + "），实穿复用率进一步提升。");
        outfitPlanMapper.updateById(plan);

        List<OutfitDto.ProductVo> products = commerceMatchService.findByIds(jsonUtils.toLongList(plan.getRecommendedProductIds()));
        return buildPlanVo(plan, wardrobe, null, products, false);
    }

    public List<OutfitDto.PlanVo> listPlans(Long userId, boolean favoriteOnly) {
        LambdaQueryWrapper<OutfitPlan> qw = new LambdaQueryWrapper<OutfitPlan>()
                .eq(OutfitPlan::getUserId, userId)
                .orderByDesc(OutfitPlan::getCreatedAt);
        if (favoriteOnly) {
            qw.eq(OutfitPlan::getIsFavorite, 1);
        }
        List<OutfitPlan> plans = outfitPlanMapper.selectList(qw);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        return plans.stream().map(p -> {
            List<OutfitDto.ProductVo> products = commerceMatchService.findByIds(jsonUtils.toLongList(p.getRecommendedProductIds()));
            return buildPlanVo(p, wardrobe, null, products, false);
        }).collect(Collectors.toList());
    }

    public OutfitDto.PlanVo toggleFavorite(Long userId, Long planId) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        int nextFav = (plan.getIsFavorite() != null && plan.getIsFavorite() == 1) ? 0 : 1;
        plan.setIsFavorite(nextFav);
        outfitPlanMapper.updateById(plan);

        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        List<Long> ids = jsonUtils.toLongList(plan.getItemIds());
        List<WardrobeItem> involved = wardrobe.stream().filter(w -> ids.contains(w.getId())).collect(Collectors.toList());
        preferenceService.reinforceStyleWeights(userId, involved, nextFav == 1 ? 0.05 : -0.02);

        List<OutfitDto.ProductVo> products = commerceMatchService.findByIds(jsonUtils.toLongList(plan.getRecommendedProductIds()));
        return buildPlanVo(plan, wardrobe, null, products, false);
    }

    public OutfitDto.PlanVo dislikePlan(Long userId, Long planId) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        List<Long> rejectedIds = jsonUtils.toLongList(plan.getItemIds());
        List<WardrobeItem> involved = wardrobe.stream()
                .filter(w -> rejectedIds.contains(w.getId()))
                .collect(Collectors.toList());

        // Negative behavioral feedback loop: decay style_weights for rejected combination
        preferenceService.reinforceStyleWeights(userId, involved, -0.05);

        OutfitDto.RecommendRequest regenReq = new OutfitDto.RecommendRequest();
        regenReq.setTriggerType(plan.getTriggerType() != null ? plan.getTriggerType() : "daily_weather");
        regenReq.setSceneTag(plan.getSceneTag());
        regenReq.setUserQuery(plan.getUserQuery());
        regenReq.setExcludeItemIds(rejectedIds);
        return recommend(userId, regenReq);
    }

    public void deletePlan(Long userId, Long planId) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        outfitPlanMapper.deleteById(plan.getId());
    }

    public List<OutfitDto.HistoryVo> listHistory(Long userId, String month) {
        LambdaQueryWrapper<OutfitHistory> qw = new LambdaQueryWrapper<OutfitHistory>()
                .eq(OutfitHistory::getUserId, userId)
                .orderByDesc(OutfitHistory::getWearDate);
        if (month != null && month.matches("\\d{4}-\\d{2}")) {
            YearMonth ym = YearMonth.parse(month);
            qw.ge(OutfitHistory::getWearDate, ym.atDay(1))
                    .le(OutfitHistory::getWearDate, ym.atEndOfMonth());
        }
        List<OutfitHistory> list = outfitHistoryMapper.selectList(qw);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        Map<Long, WardrobeItem> itemMap = wardrobe.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));

        return list.stream().map(h -> toHistoryVo(h, itemMap)).collect(Collectors.toList());
    }

    @Transactional
    public OutfitDto.HistoryVo recordHistory(Long userId, OutfitDto.RecordHistoryRequest req) {
        LocalDate date = req.getWearDate() != null ? req.getWearDate() : LocalDate.now();
        List<Long> ownedIds = new ArrayList<>();
        List<WardrobeItem> wornItems = new ArrayList<>();
        if (req.getItemIds() != null) {
            for (Long id : req.getItemIds()) {
                WardrobeItem item = wardrobeItemMapper.selectById(id);
                if (item != null && Objects.equals(item.getUserId(), userId)) {
                    ownedIds.add(id);
                    wornItems.add(item);
                }
            }
        }

        OutfitHistory existing = outfitHistoryMapper.selectOne(
                new LambdaQueryWrapper<OutfitHistory>()
                        .eq(OutfitHistory::getUserId, userId)
                        .eq(OutfitHistory::getWearDate, date));

        boolean isNewRecord = (existing == null);
        OutfitHistory entity = isNewRecord ? new OutfitHistory() : existing;
        entity.setUserId(userId);
        entity.setPlanId(req.getPlanId());
        entity.setThemeTitle(req.getThemeTitle() != null && !req.getThemeTitle().isBlank() ? req.getThemeTitle() : "今日精选穿搭");
        entity.setWearDate(date);
        entity.setSceneTag(req.getSceneTag() != null ? req.getSceneTag() : "日常穿搭");
        entity.setWeatherInfo(req.getWeatherInfo() != null ? req.getWeatherInfo() : "多云 18℃");
        entity.setWeatherTemp(req.getWeatherTemp() != null ? req.getWeatherTemp() : 18);
        entity.setWeatherIcon(req.getWeatherIcon() != null ? req.getWeatherIcon() : "cloud-sun");
        entity.setItemIds(jsonUtils.toJson(ownedIds));
        entity.setAiReasoning(req.getAiReasoning());
        entity.setFeedbackScore(req.getFeedbackScore() != null ? req.getFeedbackScore() : 5);
        entity.setNotes(req.getNotes() != null ? req.getNotes() : "今日通过 AI 智能搭配画板一键采用。");
        if (isNewRecord) {
            entity.setCreatedAt(LocalDateTime.now());
            outfitHistoryMapper.insert(entity);
        } else {
            outfitHistoryMapper.updateById(entity);
        }

        // Increment wear_count and update last_worn_date for every worn item
        for (WardrobeItem it : wornItems) {
            it.setWearCount((it.getWearCount() == null ? 0 : it.getWearCount()) + 1);
            it.setLastWornDate(date);
            it.setUpdatedAt(LocalDateTime.now());
            wardrobeItemMapper.updateById(it);
        }
        preferenceService.reinforceStyleWeights(userId, wornItems, 0.04);

        Map<Long, WardrobeItem> itemMap = wornItems.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));
        return toHistoryVo(entity, itemMap);
    }

    public OutfitDto.HistoryVo updateHistory(Long userId, Long historyId, OutfitDto.UpdateHistoryRequest req) {
        OutfitHistory h = outfitHistoryMapper.selectById(historyId);
        if (h == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到穿搭历史记录");
        }
        if (!Objects.equals(h.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (req.getFeedbackScore() != null) {
            int score = Math.max(1, Math.min(5, req.getFeedbackScore()));
            h.setFeedbackScore(score);
            List<Long> ids = jsonUtils.toLongList(h.getItemIds());
            if (!ids.isEmpty()) {
                List<WardrobeItem> items = wardrobeItemMapper.selectBatchIds(ids);
                double delta = score >= 4 ? 0.04 : (score <= 2 ? -0.04 : 0.0);
                preferenceService.reinforceStyleWeights(userId, items, delta);
            }
        }
        if (req.getNotes() != null) {
            h.setNotes(req.getNotes());
        }
        outfitHistoryMapper.updateById(h);

        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        Map<Long, WardrobeItem> itemMap = wardrobe.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));
        return toHistoryVo(h, itemMap);
    }

    public OutfitDto.PlanVo saveCollageLayout(Long userId, Long planId, OutfitDto.SaveCollageLayoutRequest req) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        List<OutfitDto.CollageNode> nodes = (req != null && req.getNodes() != null)
                ? req.getNodes() : Collections.emptyList();
        plan.setCollageLayout(jsonUtils.toJson(nodes));
        outfitPlanMapper.updateById(plan);

        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        List<OutfitDto.ProductVo> products = commerceMatchService.findByIds(
                jsonUtils.toLongList(plan.getRecommendedProductIds()));
        return buildPlanVo(plan, wardrobe, null, products, false);
    }

    public OutfitDto.TryOnResultVo generateTryOnPreview(Long userId, Long planId, OutfitDto.TryOnRequest req) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        OutfitDto.PlanVo planVo = buildPlanVo(plan, wardrobe, null, Collections.emptyList(), false);

        AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
        UserPreference userPref = preferenceService.getOrCreateEntity(userId);
        boolean wantRef = req == null || !Boolean.FALSE.equals(req.getUseReferencePhoto());
        String stylePreset = req != null && req.getStylePreset() != null ? req.getStylePreset() : "studio_white";
        String customPrompt = req != null && req.getCustomPrompt() != null ? req.getCustomPrompt().trim() : "";
        String generationMode = req != null && req.getGenerationMode() != null ? req.getGenerationMode().trim() : "auto";

        // Build clean English + Chinese fashion editorial prompt from actual outfit pieces
        String garmentDescriptions = planVo.getSlots().stream()
                .map(s -> s.getSlotLabel() + ": " + s.getItem().getColor() + " " + s.getItem().getName())
                .collect(Collectors.joining(", "));
        String sceneDesc = switch (stylePreset) {
            case "street_shanghai" -> "chic minimal street style editorial on Shanghai Anfu Road, soft natural afternoon light, shallow depth of field";
            case "magazine_editorial" -> "high-fashion monochrome gallery lookbook editorial, architectural minimalism, crisp studio lighting";
            case "window_light" -> "warm minimalist indoor gallery near soft window daylight, clean cream and pure white studio backdrop";
            default -> "pure clean seamless white studio background, Alta Daily minimalist full-body lookbook photography, soft diffused fashion lighting";
        };

        StringBuilder prompt = new StringBuilder();
        prompt.append("Full-body minimalist fashion editorial lookbook photo of a stylish East Asian model (height around ")
                .append(userPref.getHeightCm() != null ? userPref.getHeightCm() : 165)
                .append("cm, silhouette tailored for ").append(Objects.toString(userPref.getBodyShape(), "H型直筒"))
                .append(", color harmony for ").append(Objects.toString(userPref.getColorSeason(), "中性百搭"))
                .append(") wearing this exact coordinated outfit: ")
                .append(garmentDescriptions).append(". ")
                .append("Setting: ").append(sceneDesc).append(". ")
                .append("Theme: ").append(plan.getThemeTitle()).append(". ");
        if (!customPrompt.isBlank()) {
            prompt.append("Additional styling direction: ").append(customPrompt).append(". ");
        }
        prompt.append("Show complete head-to-toe silhouette, natural proportions, crisp fabric textures, ultra-clean commercial photography, no text or watermarks.");

        // Collect optional user model reference photo + real uploaded garment images for /images/edits
        List<MultimodalLlmClient.ReferenceImageInput> refInputs = new ArrayList<>();
        boolean usedRefPhoto = false;
        if (wantRef && creds.modelReferenceUrl() != null && !creds.modelReferenceUrl().isBlank()) {
            StorageService.StoredImage refImg = storageService.readUserLocalUpload(userId, creds.modelReferenceUrl());
            if (refImg != null) {
                refInputs.add(new MultimodalLlmClient.ReferenceImageInput("model_reference.png", refImg.mimeType(), refImg.bytes()));
                usedRefPhoto = true;
                prompt.insert(0, "Keep the person's face, hairstyle and body proportions consistent with the first reference photo. ");
            }
        }
        int garmentImgCount = 0;
        for (OutfitDto.PlanSlotVo slot : planVo.getSlots()) {
            if (garmentImgCount >= 2) break;
            String url = slot.getItem().getProcessedImageUrl();
            if (url != null && url.startsWith("/uploads/user_" + userId + "/")) {
                StorageService.StoredImage gImg = storageService.readUserLocalUpload(userId, url);
                if (gImg != null) {
                    refInputs.add(new MultimodalLlmClient.ReferenceImageInput(
                            "garment_" + slot.getSlotKey() + ".png", gImg.mimeType(), gImg.bytes()));
                    garmentImgCount++;
                }
            }
        }

        if (creds.active()) {
            try {
                MultimodalLlmClient.GeneratedImageResult gen = llmClient.generateEditorialTryOn(
                        creds, prompt.toString(), refInputs, generationMode);
                String finalUrl;
                if (gen.imageBytes() != null && gen.imageBytes().length > 100) {
                    StorageService.StoredImage saved = storageService.storeGeneratedImageBytes(userId, gen.imageBytes(), "tryon");
                    finalUrl = saved.url();
                } else {
                    finalUrl = gen.remoteUrl();
                }
                plan.setTryOnImageUrl(finalUrl);
                outfitPlanMapper.updateById(plan);

                boolean refApplied = usedRefPhoto && gen.endpointUsed() != null
                        && (gen.endpointUsed().startsWith("/images/edits") || gen.endpointUsed().startsWith("/chat/completions"));

                return OutfitDto.TryOnResultVo.builder()
                        .planId(planId)
                        .previewImageUrl(finalUrl)
                        .usedRealImageModel(true)
                        .usedReferencePhoto(refApplied)
                        .modelUsed(creds.imageModel())
                        .endpointUsed(gen.endpointUsed())
                        .promptUsed(prompt.toString())
                        .notice("已通过服务端生图接口（" + creds.imageModel() + " · " + gen.endpointUsed() + "）生成上身杂志预览图")
                        .build();
            } catch (Exception e) {
                log.warn("Real image model try-on failed ({}), degrading to curated editorial preview", e.getMessage());
                String fallbackUrl = pickCuratedEditorialFallback(planVo);
                plan.setTryOnImageUrl(fallbackUrl);
                outfitPlanMapper.updateById(plan);
                String errMsg = Objects.toString(e.getMessage(), "");
                String hint = errMsg.contains("HTTP 524") || errMsg.contains("HTTP 504")
                        ? "上游反代/CDN 等待超时（" + errMsg + "）：生图实际耗时约 25s，超过了域名网关/CDN 的 15s 超时限制；建议在 Base URL 改用直连地址或调大反代超时时间。"
                        : "上游生图接口返回异常（" + errMsg + "），已平滑降级为匹配该色系的杂志上身示意样片";
                return OutfitDto.TryOnResultVo.builder()
                        .planId(planId)
                        .previewImageUrl(fallbackUrl)
                        .usedRealImageModel(false)
                        .usedReferencePhoto(false)
                        .modelUsed(creds.imageModel())
                        .endpointUsed("editorial_fallback")
                        .promptUsed(prompt.toString())
                        .notice(hint)
                        .build();
            }
        }

        // Graceful fallback when no API key is configured
        String fallbackUrl = pickCuratedEditorialFallback(planVo);
        plan.setTryOnImageUrl(fallbackUrl);
        outfitPlanMapper.updateById(plan);
        return OutfitDto.TryOnResultVo.builder()
                .planId(planId)
                .previewImageUrl(fallbackUrl)
                .usedRealImageModel(false)
                .usedReferencePhoto(false)
                .modelUsed(creds.imageModel())
                .endpointUsed("editorial_fallback")
                .promptUsed(prompt.toString())
                .notice("尚未配置支持生图的中转站 API Key，当前展示匹配本套色系与风格的杂志示意样片；前往「我的 → AI 配置」填好 Key 即可实时生成真实上身大片")
                .build();
    }

    private String pickCuratedEditorialFallback(OutfitDto.PlanVo planVo) {
        String joined = (Objects.toString(planVo.getThemeTitle(), "") + " " +
                planVo.getSlots().stream().map(s -> s.getItem().getName() + " " + s.getItem().getColor()).collect(Collectors.joining(" ")))
                .toLowerCase();
        if (joined.contains("西装") || joined.contains("面试")) {
            return "/alta/looks/w2.webp";
        }
        if (joined.contains("美拉德") || joined.contains("焦糖") || joined.contains("麂皮") || joined.contains("风衣")) {
            return "/alta/looks/w3.webp";
        }
        if (joined.contains("裙") || joined.contains("约会")) {
            return "/alta/looks/w5.webp";
        }
        if (joined.contains("黑") || joined.contains("冷淡")) {
            return "/alta/looks/w10.webp";
        }
        return "/alta/looks/w8.webp";
    }

    public OutfitDto.PlanVo getPlanById(Long userId, Long planId) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        List<OutfitDto.ProductVo> products = commerceMatchService.findByIds(
                jsonUtils.toLongList(plan.getRecommendedProductIds()));
        return buildPlanVo(plan, wardrobe, null, products, false);
    }

    public OutfitDto.PlanVo applyTryOnCover(Long userId, Long planId, String previewImageUrl) {
        OutfitPlan plan = requireOwnedPlan(userId, planId);
        plan.setTryOnImageUrl(previewImageUrl);
        outfitPlanMapper.updateById(plan);
        return getPlanById(userId, planId);
    }

    public OutfitPlan requireOwnedPlan(Long userId, Long planId) {
        OutfitPlan plan = outfitPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该穿搭方案");
        }
        if (!Objects.equals(plan.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return plan;
    }

    private OutfitDto.PlanVo buildPlanVo(OutfitPlan plan, List<WardrobeItem> wardrobe, Long anchorId,
                                         List<OutfitDto.ProductVo> products, boolean usedRealLlm) {
        Map<Long, WardrobeItem> map = wardrobe.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));
        List<Long> ids = jsonUtils.toLongList(plan.getItemIds());
        List<OutfitDto.MissingSlotVo> missing = parseMissing(plan.getMissingItems());

        List<OutfitDto.PlanSlotVo> slots = new ArrayList<>();
        // Put missing outerwear first if present so slot 0 renders tall in the Flat-Lay collage
        for (OutfitDto.MissingSlotVo m : missing) {
            if ("outerwear".equals(m.getCategory())) {
                slots.add(missingToSlot(m));
            }
        }
        // Order owned items cleanly by category order
        List<WardrobeItem> ownedOrdered = ids.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(i -> {
                    int idx = SLOT_ORDER.indexOf(i.getCategory());
                    return idx >= 0 ? idx : 99;
                }))
                .collect(Collectors.toList());
        for (WardrobeItem item : ownedOrdered) {
            slots.add(OutfitDto.PlanSlotVo.builder()
                    .slotKey(item.getCategory())
                    .slotLabel(SLOT_LABELS.getOrDefault(item.getCategory(), "单品"))
                    .isMissing(false)
                    .isAnchor(anchorId != null && Objects.equals(anchorId, item.getId()))
                    .item(wardrobeService.toVo(item))
                    .build());
        }
        for (OutfitDto.MissingSlotVo m : missing) {
            if (!"outerwear".equals(m.getCategory())) {
                slots.add(missingToSlot(m));
            }
        }

        WeatherVo weatherSnap = jsonUtils.fromJson(plan.getWeatherSnapshot(), WeatherVo.class, null);
        List<OutfitDto.CollageNode> collageNodes = jsonUtils.fromJson(
                plan.getCollageLayout(), new TypeReference<List<OutfitDto.CollageNode>>() {}, Collections.emptyList());
        return OutfitDto.PlanVo.builder()
                .id(plan.getId())
                .themeTitle(plan.getThemeTitle())
                .triggerType(plan.getTriggerType())
                .sceneTag(plan.getSceneTag())
                .weatherSnapshot(weatherSnap)
                .userQuery(plan.getUserQuery())
                .slots(slots)
                .missingItems(missing)
                .recommendedProducts(products != null ? products : Collections.emptyList())
                .aiReasoning(plan.getAiReasoning())
                .matchScore(plan.getMatchScore() != null ? plan.getMatchScore() : new BigDecimal("95.0"))
                .collageLayout(collageNodes)
                .tryOnImageUrl(plan.getTryOnImageUrl())
                .isFavorite(plan.getIsFavorite() != null && plan.getIsFavorite() == 1)
                .usedRealLlm(usedRealLlm)
                .createdAt(plan.getCreatedAt())
                .build();
    }

    private OutfitDto.PlanSlotVo missingToSlot(OutfitDto.MissingSlotVo m) {
        String svgType = m.getSvgType() != null ? m.getSvgType() : wardrobeService.inferSvgType(m.getCategory(), m.getName());
        WardrobeDto.ItemVo virtual = WardrobeDto.ItemVo.builder()
                .id(-Math.abs((long) Objects.hash(m.getCategory(), m.getName())))
                .name(m.getName())
                .category(m.getCategory())
                .color(m.getColor())
                .colorHex(m.getColorHex() != null ? m.getColorHex() : "#DFD3C3")
                .svgType(svgType)
                .processedImageUrl(SeedDataInitializer.resolveAltaPieceCutout(svgType, m.getName(), m.getColor()))
                .styleTags(List.of("建议补全"))
                .wearCount(0)
                .build();
        return OutfitDto.PlanSlotVo.builder()
                .slotKey(m.getCategory())
                .slotLabel(SLOT_LABELS.getOrDefault(m.getCategory(), "补全"))
                .isMissing(true)
                .isAnchor(false)
                .item(virtual)
                .build();
    }

    private OutfitDto.HistoryVo toHistoryVo(OutfitHistory h, Map<Long, WardrobeItem> itemMap) {
        List<Long> ids = jsonUtils.toLongList(h.getItemIds());
        List<WardrobeDto.ItemVo> items = ids.stream()
                .map(itemMap::get)
                .filter(Objects::nonNull)
                .map(wardrobeService::toVo)
                .collect(Collectors.toList());
        List<String> palette = items.stream()
                .map(WardrobeDto.ItemVo::getColorHex)
                .filter(Objects::nonNull)
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
        if (palette.isEmpty()) palette = List.of("#DFD3C3");

        return OutfitDto.HistoryVo.builder()
                .id(h.getId())
                .planId(h.getPlanId())
                .themeTitle(h.getThemeTitle() != null ? h.getThemeTitle() : "当日精选穿搭")
                .wearDate(h.getWearDate())
                .sceneTag(h.getSceneTag())
                .weatherInfo(h.getWeatherInfo())
                .weatherTemp(h.getWeatherTemp() != null ? h.getWeatherTemp() : 18)
                .weatherIcon(h.getWeatherIcon() != null ? h.getWeatherIcon() : "cloud-sun")
                .itemIds(ids)
                .items(items)
                .palette(palette)
                .aiReasoning(h.getAiReasoning())
                .feedbackScore(h.getFeedbackScore() != null ? h.getFeedbackScore() : 5)
                .notes(h.getNotes())
                .build();
    }

    private WeatherVo resolveWeather(UserPreference pref, OutfitDto.RecommendRequest req) {
        String city = req.getWeatherCity() != null && !req.getWeatherCity().isBlank()
                ? req.getWeatherCity() : pref.getDefaultCity();
        if (req.getWeatherTemp() != null) {
            int t = req.getWeatherTemp();
            String cond = req.getWeatherCondition() != null ? req.getWeatherCondition() : (t >= 25 ? "晴朗" : (t <= 11 ? "小雨" : "多云"));
            String icon = req.getWeatherIcon() != null ? req.getWeatherIcon() : (t >= 25 ? "sun" : (t <= 11 ? "cloud-rain" : "cloud-sun"));
            String tip = t >= 25 ? "轻薄透气" : (t <= 11 ? "保暖防风" : "适宜叠穿");
            return WeatherVo.builder().city(city).temp(t).condition(cond).icon(icon).tip(tip).live(false).build();
        }
        return weatherApiClient.getCurrentWeather(city);
    }

    private String resolveScene(String triggerType, OutfitDto.RecommendRequest req, WeatherVo weather) {
        if (req.getSceneTag() != null && !req.getSceneTag().isBlank()) return req.getSceneTag();
        if ("anchor_item".equals(triggerType)) return "单品锚点驱动";
        if ("nl_query".equals(triggerType) && req.getUserQuery() != null) {
            if (req.getUserQuery().contains("面试")) return "场景定制 · 互联网面试";
            if (req.getUserQuery().contains("约会")) return "场景定制 · 浪漫约会";
            return "场景定制 · 灵感穿搭";
        }
        return weather.getCondition() + " " + weather.getTemp() + "℃ · " + weather.getTip();
    }

    private void pickBest(List<ScoredItem> scored, List<WardrobeItem> all, Map<String, WardrobeItem> chosen,
                          String cat, String prefer1, String prefer2) {
        Optional<WardrobeItem> preferred = findMatch(all, cat, prefer1, prefer2);
        if (preferred.isPresent()) {
            chosen.put(cat, preferred.get());
            return;
        }
        scored.stream().map(s -> s.item).filter(i -> cat.equals(i.getCategory())).findFirst()
                .or(() -> all.stream().filter(i -> cat.equals(i.getCategory())).findFirst())
                .ifPresent(it -> chosen.put(cat, it));
    }

    private Optional<WardrobeItem> findMatch(List<WardrobeItem> items, String cat, String k1, String k2) {
        return items.stream()
                .filter(i -> cat.equals(i.getCategory()))
                .filter(i -> {
                    String h = Objects.toString(i.getName(), "") + Objects.toString(i.getColor(), "") + Objects.toString(i.getStyleTags(), "");
                    return (k1 != null && h.contains(k1)) || (k2 != null && h.contains(k2));
                })
                .findFirst();
    }

    private List<Long> orderedIds(Map<String, WardrobeItem> chosen) {
        List<Long> ids = new ArrayList<>();
        for (String cat : SLOT_ORDER) {
            if (chosen.containsKey(cat)) ids.add(chosen.get(cat).getId());
        }
        return ids;
    }

    private List<OutfitDto.MissingSlotVo> parseMissing(String json) {
        return jsonUtils.fromJson(json, new TypeReference<List<OutfitDto.MissingSlotVo>>() {}, Collections.emptyList());
    }

    private OutfitDto.MissingSlotVo trenchMissing() {
        return OutfitDto.MissingSlotVo.builder()
                .category("outerwear").name("米色短款双排扣风衣").color("燕麦").colorHex("#DFD3C3")
                .svgType("trench").keywords("米色 短款 风衣 通勤").reason("微凉多云天气外搭短款风衣更显比例与质感").build();
    }

    private OutfitDto.MissingSlotVo suedeMissing() {
        return OutfitDto.MissingSlotVo.builder()
                .category("outerwear").name("美拉德翻毛麂皮夹克").color("焦糖棕").colorHex("#8A5230")
                .svgType("suede_jacket").keywords("美拉德 麂皮 夹克 外套").reason("秋冬季暖调防风外套，点亮基础款内搭").build();
    }

    private OutfitDto.MissingSlotVo stripedTeeMissing() {
        return OutfitDto.MissingSlotVo.builder()
                .category("top").name("法式条纹海魂衫").color("牛仔蓝").colorHex("#3B5372")
                .svgType("striped_shirt").keywords("法式 条纹 海魂衫").reason("清爽透气百搭基础款").build();
    }

    public record ScoredItem(WardrobeItem item, double score) {}

    private record StylistOutput(String themeTitle, List<Long> selectedItemIds,
                                 List<OutfitDto.MissingSlotVo> missingItems, String aiReasoning, double matchScore) {}
}
