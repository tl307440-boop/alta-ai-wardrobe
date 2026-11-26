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
import com.wardrobe.service.client.DashScopeVtonClient;
import com.wardrobe.service.client.MultimodalLlmClient;
import com.wardrobe.service.client.WeatherApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final DashScopeVtonClient dashScopeVtonClient;
    private final StorageService storageService;
    private final OutfitContactSheetService contactSheetService;
    private final JsonUtils jsonUtils;

    @Value("${app.public-base-url:}")
    private String configuredPublicBaseUrl;

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
                output = callLlmStylist(userId, creds, scored, weather, scene, req, anchor, basePlan, pref, budget);
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

        return buildPlanVo(plan, wardrobe, anchor != null ? anchor.getId() : null, recommendedProducts, usedRealLlm, output.usedVisualReview);
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

    private StylistOutput callLlmStylist(Long userId, AiConfigService.ResolvedAiCredentials creds, List<ScoredItem> scored,
                                         WeatherVo weather, String scene, OutfitDto.RecommendRequest req,
                                         WardrobeItem anchor, OutfitPlan basePlan, UserPreference pref, BigDecimal budget) {
        // Select top <=16 items for Visual Contact Sheet (ensuring locked anchor is always included)
        List<WardrobeItem> sheetItems = new ArrayList<>();
        if (anchor != null) {
            sheetItems.add(anchor);
        }
        for (ScoredItem si : scored) {
            if (sheetItems.size() >= 16) break;
            if (anchor == null || !Objects.equals(si.item.getId(), anchor.getId())) {
                sheetItems.add(si.item);
            }
        }
        OutfitContactSheetService.ContactSheet sheet = contactSheetService.buildContactSheet(userId, sheetItems);
        Map<Long, String> idToSheetTag = new HashMap<>();
        if (sheet != null && sheet.tagToItemId() != null) {
            for (Map.Entry<Integer, Long> entry : sheet.tagToItemId().entrySet()) {
                idToSheetTag.put(entry.getValue(), "#" + entry.getKey());
            }
        }

        List<Map<String, Object>> candidates = scored.stream().limit(25).map(si -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", si.item.getId());
            if (idToSheetTag.containsKey(si.item.getId())) {
                m.put("sheetTag", idToSheetTag.get(si.item.getId()));
            }
            m.put("name", si.item.getName());
            m.put("category", si.item.getCategory());
            m.put("color", si.item.getColor());
            m.put("material", Objects.toString(si.item.getMaterial(), ""));
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
        prompt.append("按个性化公式打分排序后的候选衣橱 JSON（其中 sheetTag 对应附图的 #编号）：\n")
                .append(jsonUtils.toJson(candidates));

        // Prefer VLM Visual Contact Sheet curation; gracefully degrade to text chatModel if vision fails
        JsonNode json = null;
        boolean usedVisualReview = false;
        if (sheet != null && sheet.dataUri() != null && creds.visionModel() != null && !creds.visionModel().isBlank()) {
            try {
                json = llmClient.curateOutfitPlanWithContactSheet(creds, prompt.toString(), sheet.dataUri());
                if (json != null && json.path("selectedItemIds").isArray() && !json.path("selectedItemIds").isEmpty()) {
                    usedVisualReview = true;
                } else {
                    json = null;
                }
            } catch (Exception ve) {
                log.warn("VLM contact-sheet review unavailable ({}), falling back to text stylist", ve.getMessage());
            }
        }
        if (json == null) {
            json = llmClient.curateOutfitPlan(creds, prompt.toString());
        }

        Map<Long, WardrobeItem> itemById = scored.stream()
                .collect(Collectors.toMap(s -> s.item.getId(), s -> s.item, (a, b) -> a));
        if (anchor != null) {
            itemById.put(anchor.getId(), anchor);
        }

        List<Long> selectedIds = new ArrayList<>();
        if (json != null && json.path("selectedItemIds").isArray()) {
            for (JsonNode idNode : json.path("selectedItemIds")) {
                long id = idNode.asLong(-1);
                if (itemById.containsKey(id) && !selectedIds.contains(id)) {
                    selectedIds.add(id);
                }
            }
        }
        if (anchor != null && !selectedIds.contains(anchor.getId())) {
            selectedIds.add(0, anchor.getId());
        }

        // Category deduplication + dress vs top/bottom exclusivity sanity guard
        selectedIds = sanitizeSelectedIds(selectedIds, itemById, anchor != null ? anchor.getId() : null);
        if (selectedIds.isEmpty()) return null;

        List<OutfitDto.MissingSlotVo> missing = new ArrayList<>();
        if (json != null && json.path("missingItems").isArray()) {
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
        String title = json != null ? json.path("themeTitle").asText("AI 定制 · 杂志级穿搭") : "AI 定制 · 杂志级穿搭";
        String reasoning = json != null
                ? json.path("aiReasoning").asText("已结合实时天气与个人风格权重从衣橱中优先挑选高匹配单品完成协同搭配。")
                : "已结合实时天气与个人风格权重从衣橱中优先挑选高匹配单品完成协同搭配。";
        double score = json != null ? Math.max(82.0, Math.min(99.0, json.path("matchScore").asDouble(95.0))) : 95.0;
        return new StylistOutput(title, selectedIds, missing, reasoning, score, usedVisualReview);
    }

    private List<Long> sanitizeSelectedIds(List<Long> rawIds, Map<Long, WardrobeItem> itemById, Long anchorId) {
        Map<String, Long> byCategory = new LinkedHashMap<>();
        boolean hasDress = false;
        for (Long id : rawIds) {
            WardrobeItem item = itemById.get(id);
            if (item == null) continue;
            String cat = Objects.toString(item.getCategory(), "top");
            String name = Objects.toString(item.getName(), "");
            boolean isDress = "dress".equalsIgnoreCase(cat) || name.contains("连衣裙");
            if (isDress) {
                hasDress = true;
            }
            if (!byCategory.containsKey(cat) || Objects.equals(id, anchorId)) {
                byCategory.put(cat, id);
            }
        }
        if (hasDress) {
            Long topId = byCategory.get("top");
            Long bottomId = byCategory.get("bottom");
            if (topId != null && !Objects.equals(topId, anchorId) && !Objects.toString(itemById.get(topId).getName(), "").contains("连衣裙")) {
                byCategory.remove("top");
            }
            if (bottomId != null && !Objects.equals(bottomId, anchorId) && !Objects.toString(itemById.get(bottomId).getName(), "").contains("连衣裙")) {
                byCategory.remove("bottom");
            }
        }
        return new ArrayList<>(byCategory.values());
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
        Set<Long> excludeSet = req.getExcludeItemIds() != null
                ? new HashSet<>(req.getExcludeItemIds()) : Collections.emptySet();
        int temp = weather.getTemp();
        if (temp >= 25) {
            pickBest(scored, wardrobe, chosen, "top", "白T", "纯白", excludeSet);
            pickBest(scored, wardrobe, chosen, "bottom", "裙", "牛仔", excludeSet);
            pickBest(scored, wardrobe, chosen, "shoes", "德训", "乐福", excludeSet);
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包", excludeSet);
            List<OutfitDto.MissingSlotVo> missing = chosen.isEmpty() ? List.of(stripedTeeMissing()) : Collections.emptyList();
            return new StylistOutput("清透燕麦 · 晴暖出街", orderedIds(chosen), missing,
                    temp + "℃ 晴暖天气已自动过滤厚外套，优先挑选轻盈透气单品，延续低饱和燕麦画廊美学。", 94.0);
        } else if (temp <= 11) {
            pickBest(scored, wardrobe, chosen, "outerwear", "大衣", "外套", excludeSet);
            pickBest(scored, wardrobe, chosen, "top", "背心", "针织", excludeSet);
            pickBest(scored, wardrobe, chosen, "bottom", "阔腿", "牛仔", excludeSet);
            pickBest(scored, wardrobe, chosen, "shoes", "乐福", "德训", excludeSet);
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包", excludeSet);
            List<OutfitDto.MissingSlotVo> missing = chosen.containsKey("outerwear")
                    ? Collections.emptyList() : List.of(suedeMissing());
            return new StylistOutput("美拉德防风 · 降温保暖", orderedIds(chosen), missing,
                    temp + "℃ 降温天气优先保障锁温与防风，内搭针织叠穿并兼顾暖调质感。", 95.0);
        } else {
            if (!excludeSet.isEmpty()) {
                pickBest(scored, wardrobe, chosen, "outerwear", "风衣", "西装", excludeSet);
            }
            pickBest(scored, wardrobe, chosen, "top", "白T", "纯白", excludeSet);
            pickBest(scored, wardrobe, chosen, "bottom", "牛仔", "阔腿", excludeSet);
            pickBest(scored, wardrobe, chosen, "shoes", "乐福", "德训", excludeSet);
            pickBest(scored, wardrobe, chosen, "accessory", "托特", "包", excludeSet);
            List<OutfitDto.MissingSlotVo> missing = chosen.containsKey("outerwear")
                    ? Collections.emptyList() : List.of(trenchMissing());
            return new StylistOutput("温柔通勤 · 轻松出门", orderedIds(chosen), missing,
                    weather.getCity() + "当前 " + temp + "℃ " + weather.getCondition()
                            + "，以衣橱已有单品构建利落基底，鞋包呼应大地色系，轻松拿捏通勤松弛感。",
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
        if (req.getOotdPhotoUrl() != null && !req.getOotdPhotoUrl().isBlank()
                && req.getOotdPhotoUrl().startsWith("/uploads/user_" + userId + "/")) {
            entity.setOotdPhotoUrl(req.getOotdPhotoUrl().trim());
        }
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

    public OutfitDto.HistoryVo uploadHistoryPhoto(Long userId, Long historyId, MultipartFile file) {
        OutfitHistory h = requireOwnedHistory(userId, historyId);
        StorageService.StoredImage stored = storageService.validateAndStoreImage(userId, file, "ootd");
        h.setOotdPhotoUrl(stored.url());
        outfitHistoryMapper.updateById(h);
        return toHistoryVoForUser(userId, h);
    }

    public OutfitDto.HistoryVo deleteHistoryPhoto(Long userId, Long historyId) {
        OutfitHistory h = requireOwnedHistory(userId, historyId);
        h.setOotdPhotoUrl(null);
        outfitHistoryMapper.updateById(h);
        return toHistoryVoForUser(userId, h);
    }

    @Transactional
    public OutfitDto.HistoryVo checkinOotd(Long userId, MultipartFile file, LocalDate wearDate, String themeTitle,
                                           String sceneTag, String weatherInfo, Integer weatherTemp, String weatherIcon,
                                           String itemIdsJson, String notes, Integer feedbackScore) {
        StorageService.StoredImage stored = storageService.validateAndStoreImage(userId, file, "ootd");
        List<Long> parsedIds = parseFlexibleItemIds(itemIdsJson);

        OutfitDto.RecordHistoryRequest req = new OutfitDto.RecordHistoryRequest();
        req.setWearDate(wearDate != null ? wearDate : LocalDate.now());
        req.setThemeTitle(themeTitle != null && !themeTitle.isBlank() ? themeTitle.trim() : "实拍 OOTD 打卡");
        req.setSceneTag(sceneTag != null && !sceneTag.isBlank() ? sceneTag.trim() : "日常实拍");
        req.setWeatherInfo(weatherInfo != null && !weatherInfo.isBlank() ? weatherInfo.trim() : "实拍记录");
        req.setWeatherTemp(weatherTemp != null ? weatherTemp : 18);
        req.setWeatherIcon(weatherIcon != null && !weatherIcon.isBlank() ? weatherIcon.trim() : "sun");
        req.setItemIds(parsedIds);
        req.setOotdPhotoUrl(stored.url());
        req.setNotes(notes != null && !notes.isBlank() ? notes.trim() : "通过穿搭日历上传真人实拍 OOTD。");
        req.setFeedbackScore(feedbackScore != null ? Math.max(1, Math.min(5, feedbackScore)) : 5);
        return recordHistory(userId, req);
    }

    /**
     * AI Outfit Visual Critique ("AI 搭配体检"): evaluates a flat-lay canvas export or real mirror OOTD photo
     * using the user's configured Vision model, with a smart deterministic closet-aware fallback when no key is set.
     */
    public OutfitDto.OutfitCritiqueVo critiqueOutfit(Long userId, MultipartFile file, String sceneTag,
                                                     String weatherInfo, String itemIdsJson, String source) {
        StorageService.StoredImage validated = storageService.validateImageOnly(file);
        UserPreference pref = preferenceService.getOrCreateEntity(userId);
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        Map<Long, WardrobeItem> wardrobeById = wardrobe.stream()
                .collect(Collectors.toMap(WardrobeItem::getId, i -> i));

        List<Long> usedIds = parseFlexibleItemIds(itemIdsJson).stream()
                .filter(wardrobeById::containsKey)
                .distinct()
                .collect(Collectors.toList());
        List<WardrobeItem> usedItems = usedIds.stream().map(wardrobeById::get).collect(Collectors.toList());
        Set<Long> usedIdSet = new HashSet<>(usedIds);

        List<Map<String, Object>> swapCandidates = wardrobe.stream()
                .filter(w -> !usedIdSet.contains(w.getId()))
                .limit(16)
                .map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", w.getId());
                    m.put("name", w.getName());
                    m.put("category", w.getCategory());
                    m.put("color", w.getColor());
                    m.put("styleTags", jsonUtils.toStringList(w.getStyleTags()));
                    return m;
                })
                .collect(Collectors.toList());

        AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
        if (creds.active() && creds.visionModel() != null && !creds.visionModel().isBlank()) {
            try {
                StringBuilder prompt = new StringBuilder();
                prompt.append("图片类型：").append("ootd_photo".equalsIgnoreCase(source)
                        ? "真人对镜/出街实拍 OOTD 照片" : "衣橱无限画布平铺拼贴搭配图").append("\n");
                if (sceneTag != null && !sceneTag.isBlank()) {
                    prompt.append("目标场合：").append(sceneTag.trim()).append("\n");
                }
                if (weatherInfo != null && !weatherInfo.isBlank()) {
                    prompt.append("天气信息：").append(weatherInfo.trim()).append("\n");
                }
                prompt.append("用户身形：").append(Objects.toString(pref.getBodyShape(), "H型直筒"))
                        .append("（身高 ").append(pref.getHeightCm() != null ? pref.getHeightCm() : 165).append("cm），")
                        .append("色彩季型：").append(Objects.toString(pref.getColorSeason(), "中性百搭")).append("\n");
                prompt.append("偏好风格：").append(Objects.toString(pref.getFavoriteStyles(), "[]"))
                        .append("，避雷色：").append(Objects.toString(pref.getDislikedColors(), "[]")).append("\n");
                if (!usedItems.isEmpty()) {
                    String usedSummary = usedItems.stream()
                            .map(i -> i.getName() + "(" + SLOT_LABELS.getOrDefault(i.getCategory(), i.getCategory()) + "/" + i.getColor() + ")")
                            .collect(Collectors.joining("、"));
                    prompt.append("已知包含单品：").append(usedSummary).append("\n");
                }
                prompt.append("用户衣橱可替换候选列表 JSON：\n").append(jsonUtils.toJson(swapCandidates));

                String dataUri = storageService.toDataUri(validated.bytes(), validated.mimeType());
                JsonNode json = llmClient.critiqueOutfitVisual(creds, dataUri, prompt.toString());
                if (json != null) {
                    int colorScore = clampScore(json.path("colorScore").asInt(86));
                    int proportionScore = clampScore(json.path("proportionScore").asInt(84));
                    int sceneScore = clampScore(json.path("sceneScore").asInt(87));
                    int overall = json.has("overallScore")
                            ? clampScore(json.path("overallScore").asInt((colorScore + proportionScore + sceneScore) / 3))
                            : Math.round((colorScore + proportionScore + sceneScore) / 3.0f);

                    List<String> highlights = readStringArray(json.path("highlights"), 3);
                    List<String> improvements = readStringArray(json.path("improvements"), 3);
                    if (highlights.isEmpty()) highlights = List.of("整体视觉调性克制耐看，主次层次分明");
                    if (improvements.isEmpty()) improvements = List.of("可通过塞衣角或增加金属/皮质小配饰进一步拉升精致度");

                    OutfitDto.SuggestedSwapVo swapVo = null;
                    JsonNode swapNode = json.path("suggestedSwap");
                    if (swapNode != null && swapNode.isObject() && !swapNode.isNull()) {
                        long swapId = swapNode.path("itemId").asLong(-1);
                        WardrobeItem ownedSwap = wardrobeById.get(swapId);
                        if (ownedSwap != null) {
                            swapVo = OutfitDto.SuggestedSwapVo.builder()
                                    .itemId(ownedSwap.getId())
                                    .itemName(ownedSwap.getName())
                                    .category(ownedSwap.getCategory())
                                    .reason(swapNode.path("reason").asText("替换后整体色彩与比例会更协调"))
                                    .build();
                        }
                    }

                    String headline = json.path("headline").asText("").trim();
                    if (headline.isBlank()) {
                        headline = overall >= 88 ? "色彩与廓形协调度很高，可直接出门" : "基础框架稳妥，微调比例会更出片";
                    }

                    return OutfitDto.OutfitCritiqueVo.builder()
                            .overallScore(overall)
                            .colorScore(colorScore)
                            .proportionScore(proportionScore)
                            .sceneScore(sceneScore)
                            .headline(headline)
                            .highlights(highlights)
                            .improvements(improvements)
                            .suggestedSwap(swapVo)
                            .usedRealVlm(true)
                            .modelUsed(creds.visionModel())
                            .notice("已由多模态视觉模型（" + creds.visionModel() + "）完成审图体检")
                            .build();
                }
            } catch (Exception e) {
                log.warn("VLM outfit critique failed ({}), degrading to local stylist critique", e.getMessage());
            }
        }

        return buildLocalCritiqueFallback(pref, wardrobe, usedItems, sceneTag, weatherInfo, source);
    }

    private OutfitDto.OutfitCritiqueVo buildLocalCritiqueFallback(UserPreference pref, List<WardrobeItem> wardrobe,
                                                                  List<WardrobeItem> usedItems, String sceneTag,
                                                                  String weatherInfo, String source) {
        List<String> dislikedColors = jsonUtils.toStringList(pref.getDislikedColors());
        List<String> seasonPalette = PreferenceService.getSeasonRecommendedColors(pref.getColorSeason());

        Set<String> distinctColors = usedItems.stream()
                .map(WardrobeItem::getColor)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> cats = usedItems.stream()
                .map(WardrobeItem::getCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int colorScore = 86;
        if (distinctColors.size() >= 2 && distinctColors.size() <= 3) colorScore += 5;
        else if (distinctColors.size() > 4) colorScore -= 8;
        if (usedItems.stream().anyMatch(i -> dislikedColors.contains(i.getColor()))) colorScore -= 12;
        if (usedItems.stream().anyMatch(i -> seasonPalette.contains(i.getColor()))) colorScore += 3;

        int proportionScore = 82;
        if (cats.contains("top") && cats.contains("bottom")) proportionScore += 5;
        if (cats.contains("outerwear")) proportionScore += 3;
        if (cats.contains("shoes")) proportionScore += 2;
        if (cats.contains("accessory")) proportionScore += 2;
        if (usedItems.size() <= 2) proportionScore -= 6;

        int sceneScore = 85;
        if (sceneTag != null && !sceneTag.isBlank()) {
            double avgScene = usedItems.isEmpty() ? 0.80 :
                    usedItems.stream().mapToDouble(i -> computeSceneMatch(i, sceneTag)).average().orElse(0.80);
            sceneScore = clampScore((int) Math.round(70 + avgScene * 26));
        }

        colorScore = clampScore(colorScore);
        proportionScore = clampScore(proportionScore);
        sceneScore = clampScore(sceneScore);
        int overall = Math.round((colorScore + proportionScore + sceneScore) / 3.0f);

        List<String> highlights = new ArrayList<>();
        if (!distinctColors.isEmpty()) {
            highlights.add("色系控制在 " + String.join(" / ", distinctColors) + "（共 " + distinctColors.size() + " 色），视觉节奏克制清爽");
        } else {
            highlights.add("画布整体构图简洁干净，保留了 Alta 极简画廊留白感");
        }
        if (cats.contains("outerwear") && cats.contains("top")) {
            highlights.add("内外叠穿层次清晰，早晚温差切换实用度高");
        } else {
            highlights.add("符合「" + Objects.toString(pref.getBodyShape(), "H型直筒") + "」日常轻盈穿搭框架");
        }

        List<String> improvements = new ArrayList<>();
        if (!cats.contains("accessory")) {
            improvements.add("目前缺少包袋/配饰收尾，加入一只焦糖或燕麦色小包能让整套完成度显著拉升");
        } else if (!cats.contains("outerwear")) {
            improvements.add("可尝试外搭一件微廓形外套或针织开衫披肩，增加纵向立体感与防风余量");
        } else {
            improvements.add(PreferenceService.getBodyShapeAdvice(pref.getBodyShape()) + "；实穿时建议注意内搭下摆与腰线比例");
        }
        if (distinctColors.size() > 3) {
            improvements.add("全身色块偏多，建议把鞋包统一回同一大地色系以收束视觉重心");
        }

        // Suggest 1 real item from the user's own wardrobe that isn't currently used
        Set<Long> usedIdSet = usedItems.stream().map(WardrobeItem::getId).collect(Collectors.toSet());
        String preferMissingCat = !cats.contains("accessory") ? "accessory"
                : (!cats.contains("outerwear") ? "outerwear"
                : (!cats.contains("shoes") ? "shoes" : "bottom"));
        WardrobeItem swapItem = wardrobe.stream()
                .filter(w -> !usedIdSet.contains(w.getId()))
                .sorted(Comparator.comparingInt((WardrobeItem w) -> preferMissingCat.equals(w.getCategory()) ? 0 : 1)
                        .thenComparingInt(w -> w.getWearCount() != null ? w.getWearCount() : 0))
                .findFirst()
                .orElse(null);

        OutfitDto.SuggestedSwapVo swapVo = null;
        if (swapItem != null) {
            String reason = !cats.contains(swapItem.getCategory())
                    ? "衣橱里这件" + SLOT_LABELS.getOrDefault(swapItem.getCategory(), "单品") + "刚好能补齐整套层次，且色系易呼应"
                    : "用衣橱里这件替换同品类单品，可盘活利用率并让比例更利落";
            swapVo = OutfitDto.SuggestedSwapVo.builder()
                    .itemId(swapItem.getId())
                    .itemName(swapItem.getName())
                    .category(swapItem.getCategory())
                    .reason(reason)
                    .build();
        }

        String headline = overall >= 88
                ? "色彩与单品完成度都很稳，微调一处配饰即出片"
                : "基础搭配框架成立，补齐层次或锚定腰线会更高级";

        return OutfitDto.OutfitCritiqueVo.builder()
                .overallScore(overall)
                .colorScore(colorScore)
                .proportionScore(proportionScore)
                .sceneScore(sceneScore)
                .headline(headline)
                .highlights(highlights)
                .improvements(improvements)
                .suggestedSwap(swapVo)
                .usedRealVlm(false)
                .modelUsed("local_stylist_rules")
                .notice("未配置多模态视觉 API Key，当前基于衣橱色系/身形规则给出体检建议；前往「我的 → AI 配置」开启视觉模型即可直接看图深度点评")
                .build();
    }

    private OutfitHistory requireOwnedHistory(Long userId, Long historyId) {
        OutfitHistory h = outfitHistoryMapper.selectById(historyId);
        if (h == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到穿搭历史记录");
        }
        if (!Objects.equals(h.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return h;
    }

    private OutfitDto.HistoryVo toHistoryVoForUser(Long userId, OutfitHistory h) {
        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        Map<Long, WardrobeItem> itemMap = wardrobe.stream().collect(Collectors.toMap(WardrobeItem::getId, i -> i));
        return toHistoryVo(h, itemMap);
    }

    private List<Long> parseFlexibleItemIds(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            return jsonUtils.toLongList(trimmed);
        }
        List<Long> ids = new ArrayList<>();
        for (String part : trimmed.split("[,\\s]+")) {
            try {
                if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private static int clampScore(int score) {
        return Math.max(50, Math.min(99, score));
    }

    private static List<String> readStringArray(JsonNode node, int max) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText("").trim();
                if (!s.isBlank()) {
                    out.add(s);
                    if (out.size() >= max) break;
                }
            }
        }
        return out;
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

        Map<Long, WardrobeItem> wardrobeById = wardrobe.stream()
                .collect(Collectors.toMap(WardrobeItem::getId, w -> w, (a, b) -> a));

        // 1. Resolve ALL real garment images (both user uploads and /alta/pieces/ studio cutouts)
        Map<String, StorageService.StoredImage> garmentImagesBySlot = new LinkedHashMap<>();
        List<OutfitDto.PlanSlotVo> allSlots = planVo.getSlots() != null ? planVo.getSlots() : Collections.emptyList();
        for (OutfitDto.PlanSlotVo slot : allSlots) {
            if (garmentImagesBySlot.size() >= 5 || slot.getItem() == null) continue;
            StorageService.StoredImage gImg = storageService.readGarmentImageForAi(userId, slot.getItem().getProcessedImageUrl());
            if (gImg == null && slot.getItem().getId() != null) {
                WardrobeItem entity = wardrobeById.get(slot.getItem().getId());
                if (entity != null) {
                    gImg = storageService.readGarmentImageForAi(userId, entity.getOriginalImageUrl());
                }
            }
            if (gImg != null && !garmentImagesBySlot.containsKey(slot.getSlotKey())) {
                garmentImagesBySlot.put(slot.getSlotKey(), gImg);
            }
        }

        // 2. Plan A — VLM Visual Feature Grounding: inspect real garment cutouts before generation
        boolean usedVlmVisualExtraction = false;
        String vlmOverallPalette = null;
        Map<String, JsonNode> extractedBySlot = new LinkedHashMap<>();
        if (creds.active() && !garmentImagesBySlot.isEmpty()) {
            try {
                StringBuilder metaBuilder = new StringBuilder();
                List<String> dataUris = new ArrayList<>();
                int idx = 1;
                for (OutfitDto.PlanSlotVo slot : allSlots) {
                    StorageService.StoredImage gImg = garmentImagesBySlot.get(slot.getSlotKey());
                    if (gImg == null) continue;
                    String uri = llmClient.toCompactVisionDataUri(
                            "garment_" + slot.getSlotKey(), gImg.mimeType(), gImg.bytes());
                    if (uri == null) continue;
                    dataUris.add(uri);
                    metaBuilder.append("[图").append(idx++).append("] ")
                            .append("slotKey=").append(slot.getSlotKey())
                            .append(" (").append(slot.getSlotLabel()).append("): ")
                            .append(Objects.toString(slot.getItem().getColor(), ""))
                            .append(" ")
                            .append(Objects.toString(slot.getItem().getName(), ""))
                            .append("\n");
                }
                if (!dataUris.isEmpty()) {
                    JsonNode vlmNode = llmClient.extractOutfitVisualDetails(creds, metaBuilder.toString(), dataUris);
                    if (vlmNode != null && vlmNode.path("items").isArray()) {
                        vlmOverallPalette = vlmNode.path("overallPalette").asText(null);
                        for (JsonNode itemNode : vlmNode.path("items")) {
                            String sKey = itemNode.path("slotKey").asText("");
                            if (!sKey.isBlank()) {
                                extractedBySlot.put(sKey, itemNode);
                            }
                        }
                        usedVlmVisualExtraction = !extractedBySlot.isEmpty();
                    }
                }
            } catch (Exception ex) {
                log.warn("VLM garment visual feature extraction fell back to wardrobe attributes: {}", ex.getMessage());
            }
        }

        // 3. Build structured piece features (Chinese editorial report + detailed English prompt)
        List<OutfitDto.VisualPieceFeatureVo> pieceFeatures = new ArrayList<>();
        for (OutfitDto.PlanSlotVo slot : allSlots) {
            WardrobeDto.ItemVo item = slot.getItem();
            if (item == null) continue;
            JsonNode extracted = extractedBySlot.get(slot.getSlotKey());
            String zhSummary = extracted != null ? extracted.path("zhSummary").asText("").trim() : "";
            String enPrompt = extracted != null ? extracted.path("enPrompt").asText("").trim() : "";
            boolean grounded = !zhSummary.isBlank() && !enPrompt.isBlank();
            if (!grounded) {
                zhSummary = buildFallbackZhSummary(slot);
                enPrompt = buildFallbackEnPrompt(slot);
            }
            pieceFeatures.add(OutfitDto.VisualPieceFeatureVo.builder()
                    .slotKey(slot.getSlotKey())
                    .slotLabel(slot.getSlotLabel())
                    .itemId(item.getId())
                    .name(item.getName())
                    .imageUrl(item.getProcessedImageUrl())
                    .colorHex(item.getColorHex())
                    .svgType(item.getSvgType())
                    .zhSummary(zhSummary)
                    .enPrompt(enPrompt)
                    .visualGrounded(grounded)
                    .build());
        }

        String garmentDescriptions = pieceFeatures.stream()
                .map(p -> p.getSlotLabel() + " (" + p.getName() + "): " + p.getEnPrompt())
                .collect(Collectors.joining("; "));

        String sceneDesc = switch (stylePreset) {
            case "street_shanghai" -> "chic minimal street style editorial on Shanghai Anfu Road, soft natural afternoon light, shallow depth of field";
            case "magazine_editorial" -> "high-fashion monochrome gallery lookbook editorial, architectural minimalism, crisp studio lighting";
            case "window_light" -> "warm minimalist indoor gallery near soft window daylight, clean cream and pure white studio backdrop";
            default -> "pure clean seamless white studio background, Alta Daily minimalist full-body lookbook photography, soft diffused fashion lighting";
        };
        String sceneLabelZh = switch (stylePreset) {
            case "street_shanghai" -> "安福路自然街拍";
            case "magazine_editorial" -> "高奢杂志 Lookbook";
            case "window_light" -> "画廊柔光大片";
            default -> "极简纯白棚拍";
        };

        int heightCm = userPref.getHeightCm() != null ? userPref.getHeightCm() : 165;
        String bodyShape = Objects.toString(userPref.getBodyShape(), "H型直筒");
        String colorSeason = Objects.toString(userPref.getColorSeason(), "中性百搭");

        // 4. Collect optional user model reference photo + ALL real garment cutouts for /images/edits or /chat/completions
        List<MultimodalLlmClient.ReferenceImageInput> refInputs = new ArrayList<>();
        boolean usedRefPhoto = false;
        if (wantRef && creds.modelReferenceUrl() != null && !creds.modelReferenceUrl().isBlank()) {
            StorageService.StoredImage refImg = storageService.readUserLocalUpload(userId, creds.modelReferenceUrl());
            if (refImg != null) {
                refInputs.add(new MultimodalLlmClient.ReferenceImageInput("model_reference.png", refImg.mimeType(), refImg.bytes()));
                usedRefPhoto = true;
            }
        }
        int attachedGarmentCount = 0;
        for (OutfitDto.PlanSlotVo slot : allSlots) {
            StorageService.StoredImage gImg = garmentImagesBySlot.get(slot.getSlotKey());
            if (gImg != null) {
                String ext = "image/png".equals(gImg.mimeType()) ? "png"
                        : "image/webp".equals(gImg.mimeType()) ? "webp" : "jpg";
                refInputs.add(new MultimodalLlmClient.ReferenceImageInput(
                        "garment_" + slot.getSlotKey() + "." + ext, gImg.mimeType(), gImg.bytes()));
                attachedGarmentCount++;
            }
        }

        StringBuilder prompt = new StringBuilder();
        if (usedRefPhoto) {
            prompt.append("Reference image 1 is the target person: keep their exact face, hairstyle, skin tone, and natural body proportions consistent. ");
        }
        if (attachedGarmentCount > 0) {
            prompt.append("The garment reference images show the exact clothing pieces to wear — faithfully reproduce each piece's silhouette, neckline, collar, color shade, fabric texture, buttons, pockets, and footwear shape without redesigning them. ");
        }
        prompt.append("Full-body minimalist fashion editorial lookbook photo of a stylish East Asian model (height around ")
                .append(heightCm)
                .append("cm, silhouette tailored for ").append(bodyShape)
                .append(", color harmony for ").append(colorSeason)
                .append(") wearing this exact coordinated outfit: ")
                .append(garmentDescriptions).append(". ")
                .append("Setting: ").append(sceneDesc).append(". ")
                .append("Theme: ").append(plan.getThemeTitle()).append(". ");
        if (!customPrompt.isBlank()) {
            prompt.append("Additional styling direction: ").append(customPrompt).append(". ");
        }
        prompt.append("Show complete head-to-toe silhouette, natural proportions, crisp fabric textures, ultra-clean commercial photography, no text or watermarks.");

        String overallPaletteZh = (vlmOverallPalette != null && !vlmOverallPalette.isBlank())
                ? vlmOverallPalette
                : buildFallbackOverallPalette(allSlots);
        String bodyProfileZh = heightCm + "cm · " + bodyShape + " · " + colorSeason;

        // 3.5 Plan C Primary Path: Alibaba Bailian Professional Virtual Try-On (aitryon / aitryon-plus)
        String vtonFallbackReason = null;
        String requestedPreset = req != null && req.getModelPreset() != null ? req.getModelPreset().trim() : "";
        if (creds.vtonActive()) {
            String effectivePublicBase = firstNonBlank(
                    creds.vtonPublicBaseUrl(),
                    configuredPublicBaseUrl,
                    req != null ? req.getClientOrigin() : null
            );
            if (StorageService.isLoopbackUrl(effectivePublicBase)) {
                vtonFallbackReason = "当前服务地址为本地回环或未配置公网 URL，阿里云百炼无法从公网拉取图片，请在「AI 模型配置」填写服务器公网地址（如 http://你的IP:8680）；已自动回退至通用生图通道";
            } else {
                try {
                    String personAssetUrl;
                    if (requestedPreset.startsWith("/alta/models/")) {
                        personAssetUrl = requestedPreset;
                    } else if (wantRef && creds.modelReferenceUrl() != null && !creds.modelReferenceUrl().isBlank()) {
                        personAssetUrl = creds.modelReferenceUrl();
                    } else {
                        personAssetUrl = "/alta/models/female-1.jpg";
                    }

                    OutfitDto.PlanSlotVo onePieceSlot = allSlots.stream()
                            .filter(this::isOnePieceGarment)
                            .findFirst()
                            .orElse(null);
                    OutfitDto.PlanSlotVo topSlot;
                    OutfitDto.PlanSlotVo bottomSlot;
                    if (onePieceSlot != null) {
                        topSlot = onePieceSlot;
                        bottomSlot = null;
                    } else {
                        OutfitDto.PlanSlotVo outerwearSlot = findSlotByKey(allSlots, "outerwear");
                        OutfitDto.PlanSlotVo topOnlySlot = findSlotByKey(allSlots, "top");
                        OutfitDto.PlanSlotVo bottomOnlySlot = findSlotByKey(allSlots, "bottom");
                        String upperPref = req != null && req.getUpperGarmentPreference() != null
                                ? req.getUpperGarmentPreference().trim().toLowerCase(Locale.ROOT)
                                : "auto";
                        if ("top".equals(upperPref)) {
                            topSlot = topOnlySlot != null ? topOnlySlot : outerwearSlot;
                        } else {
                            topSlot = outerwearSlot != null ? outerwearSlot : topOnlySlot;
                        }
                        bottomSlot = bottomOnlySlot;
                    }

                    String personPublicUrl = storageService.preparePublicVtonImageUrl(userId, personAssetUrl, effectivePublicBase);
                    String topAssetUrl = resolveSlotAssetUrl(topSlot, wardrobeById);
                    String bottomAssetUrl = resolveSlotAssetUrl(bottomSlot, wardrobeById);
                    String topPublicUrl = topAssetUrl != null
                            ? storageService.preparePublicVtonImageUrl(userId, topAssetUrl, effectivePublicBase)
                            : null;
                    String bottomPublicUrl = bottomAssetUrl != null
                            ? storageService.preparePublicVtonImageUrl(userId, bottomAssetUrl, effectivePublicBase)
                            : null;

                    if (personPublicUrl == null || (topPublicUrl == null && bottomPublicUrl == null)) {
                        vtonFallbackReason = "当前搭配缺少可送入百炼 VTON 的上装/下装单品图片或模特全身照，已自动回退至通用生图通道";
                    } else {
                        DashScopeVtonClient.VtonResult vtonRes = dashScopeVtonClient.runVirtualTryOn(
                                creds.vtonApiKey(),
                                creds.vtonModel(),
                                personPublicUrl,
                                topPublicUrl,
                                bottomPublicUrl
                        );
                        StorageService.StoredImage saved = storageService.storeGeneratedImageBytes(
                                userId, vtonRes.imageBytes(), "tryon_vton");
                        plan.setTryOnImageUrl(saved.url());
                        outfitPlanMapper.updateById(plan);

                        String appliedTopKey = (topPublicUrl != null && topSlot != null) ? topSlot.getSlotKey() : null;
                        String appliedBottomKey = (bottomPublicUrl != null && bottomSlot != null) ? bottomSlot.getSlotKey() : null;
                        List<String> appliedLabels = new ArrayList<>();
                        int appliedCount = 0;
                        for (OutfitDto.VisualPieceFeatureVo pf : pieceFeatures) {
                            boolean applied = Objects.equals(pf.getSlotKey(), appliedTopKey)
                                    || Objects.equals(pf.getSlotKey(), appliedBottomKey);
                            pf.setVtonApplied(applied);
                            if (applied) {
                                appliedCount++;
                                appliedLabels.add(pf.getSlotLabel());
                            }
                        }

                        OutfitDto.TryOnVisualReportVo vtonReport = OutfitDto.TryOnVisualReportVo.builder()
                                .usedRealVlm(usedVlmVisualExtraction)
                                .referencedGarmentCount(appliedCount)
                                .usedGarmentImagesInGen(true)
                                .engineType("dashscope_vton")
                                .vtonFallbackReason(null)
                                .overallPalette(overallPaletteZh)
                                .bodyProfile(bodyProfileZh)
                                .sceneLabel("阿里云百炼 VTON 棚拍实穿")
                                .pieces(pieceFeatures)
                                .build();

                        String appliedSummary = appliedLabels.isEmpty() ? "上/下装" : String.join("、", appliedLabels);
                        return OutfitDto.TryOnResultVo.builder()
                                .planId(planId)
                                .previewImageUrl(saved.url())
                                .usedRealImageModel(true)
                                .usedReferencePhoto(true)
                                .modelUsed(vtonRes.modelUsed())
                                .endpointUsed("dashscope/aitryon")
                                .promptUsed("")
                                .visualReport(vtonReport)
                                .notice("已通过阿里云百炼专业试衣（" + vtonRes.modelUsed() + "）完成 1:1 实穿贴合（实穿：" + appliedSummary + "），效果图已永久保存至本地")
                                .build();
                    }
                } catch (Exception ex) {
                    log.warn("DashScope VTON try-on failed ({}), falling back to general image generation", ex.getMessage());
                    String err = Objects.toString(ex.getMessage(), "未知错误");
                    if (err.length() > 120) err = err.substring(0, 120) + "...";
                    vtonFallbackReason = "百炼 VTON 调用异常（" + err + "），已自动回退至通用生图通道";
                }
            }
        }

        // If VTON fell back and user picked a standard model preset, attach that preset to general /images/edits too
        if (!usedRefPhoto && requestedPreset.startsWith("/alta/models/")) {
            StorageService.StoredImage presetImg = storageService.readGarmentImageForAi(userId, requestedPreset);
            if (presetImg != null) {
                refInputs.add(0, new MultimodalLlmClient.ReferenceImageInput("model_reference.jpg", presetImg.mimeType(), presetImg.bytes()));
                usedRefPhoto = true;
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
                boolean garmentsAppliedInGen = attachedGarmentCount > 0 && gen.endpointUsed() != null
                        && (gen.endpointUsed().startsWith("/images/edits") || gen.endpointUsed().startsWith("/chat/completions"));

                OutfitDto.TryOnVisualReportVo visualReport = OutfitDto.TryOnVisualReportVo.builder()
                        .usedRealVlm(usedVlmVisualExtraction)
                        .referencedGarmentCount(garmentImagesBySlot.size())
                        .usedGarmentImagesInGen(garmentsAppliedInGen)
                        .engineType("general_image")
                        .vtonFallbackReason(vtonFallbackReason)
                        .overallPalette(overallPaletteZh)
                        .bodyProfile(bodyProfileZh)
                        .sceneLabel(sceneLabelZh)
                        .pieces(pieceFeatures)
                        .build();

                String noticeMsg;
                if (garmentsAppliedInGen) {
                    noticeMsg = "已直接携带本套 " + attachedGarmentCount + " 件单品原图与 VLM 视觉特征（"
                            + creds.imageModel() + " · " + gen.endpointUsed() + "）完成高保真上身融合";
                } else if (usedVlmVisualExtraction) {
                    noticeMsg = "已通过 VLM 看图提取 " + garmentImagesBySlot.size() + " 件单品精细版型/面料特征并注入生图（"
                            + creds.imageModel() + " · " + gen.endpointUsed() + "）";
                } else {
                    noticeMsg = "已通过服务端生图接口（" + creds.imageModel() + " · " + gen.endpointUsed() + "）生成上身杂志预览图";
                }
                if (vtonFallbackReason != null && !vtonFallbackReason.isBlank()) {
                    noticeMsg = vtonFallbackReason + "；" + noticeMsg;
                }

                return OutfitDto.TryOnResultVo.builder()
                        .planId(planId)
                        .previewImageUrl(finalUrl)
                        .usedRealImageModel(true)
                        .usedReferencePhoto(refApplied)
                        .modelUsed(creds.imageModel())
                        .endpointUsed(gen.endpointUsed())
                        .promptUsed(prompt.toString())
                        .visualReport(visualReport)
                        .notice(noticeMsg)
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
                if (vtonFallbackReason != null && !vtonFallbackReason.isBlank()) {
                    hint = vtonFallbackReason + "；" + hint;
                }
                OutfitDto.TryOnVisualReportVo fallbackReport = OutfitDto.TryOnVisualReportVo.builder()
                        .usedRealVlm(usedVlmVisualExtraction)
                        .referencedGarmentCount(garmentImagesBySlot.size())
                        .usedGarmentImagesInGen(false)
                        .engineType("editorial_fallback")
                        .vtonFallbackReason(vtonFallbackReason)
                        .overallPalette(overallPaletteZh)
                        .bodyProfile(bodyProfileZh)
                        .sceneLabel(sceneLabelZh)
                        .pieces(pieceFeatures)
                        .build();
                return OutfitDto.TryOnResultVo.builder()
                        .planId(planId)
                        .previewImageUrl(fallbackUrl)
                        .usedRealImageModel(false)
                        .usedReferencePhoto(false)
                        .modelUsed(creds.imageModel())
                        .endpointUsed("editorial_fallback")
                        .promptUsed(prompt.toString())
                        .visualReport(fallbackReport)
                        .notice(hint)
                        .build();
            }
        }

        // Graceful fallback when no API key is configured
        String fallbackUrl = pickCuratedEditorialFallback(planVo);
        plan.setTryOnImageUrl(fallbackUrl);
        outfitPlanMapper.updateById(plan);
        OutfitDto.TryOnVisualReportVo offlineReport = OutfitDto.TryOnVisualReportVo.builder()
                .usedRealVlm(false)
                .referencedGarmentCount(garmentImagesBySlot.size())
                .usedGarmentImagesInGen(false)
                .engineType("editorial_fallback")
                .vtonFallbackReason(vtonFallbackReason)
                .overallPalette(overallPaletteZh)
                .bodyProfile(bodyProfileZh)
                .sceneLabel(sceneLabelZh)
                .pieces(pieceFeatures)
                .build();
        String offlineNotice = "尚未配置支持生图的中转站 API Key，当前展示匹配本套色系与风格的杂志示意样片；前往「我的 → AI 配置」填好 Key 即可实时激活 VLM 视觉反推与真机生图";
        if (vtonFallbackReason != null && !vtonFallbackReason.isBlank()) {
            offlineNotice = vtonFallbackReason + "；" + offlineNotice;
        }
        return OutfitDto.TryOnResultVo.builder()
                .planId(planId)
                .previewImageUrl(fallbackUrl)
                .usedRealImageModel(false)
                .usedReferencePhoto(false)
                .modelUsed(creds.imageModel())
                .endpointUsed("editorial_fallback")
                .promptUsed(prompt.toString())
                .visualReport(offlineReport)
                .notice(offlineNotice)
                .build();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private OutfitDto.PlanSlotVo findSlotByKey(List<OutfitDto.PlanSlotVo> slots, String key) {
        if (slots == null) return null;
        return slots.stream()
                .filter(s -> key.equals(s.getSlotKey()) && s.getItem() != null)
                .findFirst()
                .orElse(null);
    }

    private boolean isOnePieceGarment(OutfitDto.PlanSlotVo slot) {
        if (slot == null || slot.getItem() == null) return false;
        String cat = Objects.toString(slot.getItem().getCategory(), "");
        if ("dress".equalsIgnoreCase(cat)) return true;
        String name = Objects.toString(slot.getItem().getName(), "");
        return name.contains("连衣裙") || name.contains("连体裤") || name.contains("连体衣")
                || name.contains("背带裤") || name.contains("背带裙") || name.contains("长裙套装");
    }

    private String resolveSlotAssetUrl(OutfitDto.PlanSlotVo slot, Map<Long, WardrobeItem> wardrobeById) {
        if (slot == null || slot.getItem() == null) return null;
        String url = slot.getItem().getProcessedImageUrl();
        if (url != null && !url.isBlank() && !url.trim().startsWith("svg://")) return url;
        if (slot.getItem().getId() != null) {
            WardrobeItem entity = wardrobeById.get(slot.getItem().getId());
            if (entity != null && entity.getOriginalImageUrl() != null && !entity.getOriginalImageUrl().isBlank()) {
                return entity.getOriginalImageUrl();
            }
        }
        return null;
    }

    private String buildFallbackZhSummary(OutfitDto.PlanSlotVo slot) {
        WardrobeDto.ItemVo item = slot.getItem();
        String color = Objects.toString(item.getColor(), "").trim();
        String material = Objects.toString(item.getMaterial(), "").trim();
        String tags = item.getStyleTags() != null && !item.getStyleTags().isEmpty()
                ? String.join(" · ", item.getStyleTags().subList(0, Math.min(2, item.getStyleTags().size())))
                : "";
        List<String> parts = new ArrayList<>();
        if (!color.isBlank()) parts.add(color + "色调");
        if (!material.isBlank()) parts.add(material + "质感");
        if (!tags.isBlank()) parts.add(tags);
        if (parts.isEmpty()) {
            return Objects.toString(item.getName(), slot.getSlotLabel());
        }
        return String.join(" · ", parts);
    }

    private String buildFallbackEnPrompt(OutfitDto.PlanSlotVo slot) {
        WardrobeDto.ItemVo item = slot.getItem();
        String color = Objects.toString(item.getColor(), "").trim();
        String name = Objects.toString(item.getName(), "").trim();
        String material = Objects.toString(item.getMaterial(), "").trim();
        String matPart = material.isBlank() ? "" : " in " + material;
        return color + " " + name + matPart + ", clean tailored silhouette faithful to the garment photo";
    }

    private String buildFallbackOverallPalette(List<OutfitDto.PlanSlotVo> slots) {
        String colors = slots.stream()
                .map(s -> s.getItem() != null ? s.getItem().getColor() : null)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .limit(4)
                .collect(Collectors.joining(" + "));
        if (colors.isBlank()) {
            return "极简中性色调层次搭配，视觉干净平衡";
        }
        return "以「" + colors + "」构成柔和和谐的画廊级色彩层次";
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
        return buildPlanVo(plan, wardrobe, anchorId, products, usedRealLlm, false);
    }

    private OutfitDto.PlanVo buildPlanVo(OutfitPlan plan, List<WardrobeItem> wardrobe, Long anchorId,
                                         List<OutfitDto.ProductVo> products, boolean usedRealLlm,
                                         boolean usedVisualReview) {
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
                .usedVisualReview(usedVisualReview)
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
                .ootdPhotoUrl(h.getOotdPhotoUrl())
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
        pickBest(scored, all, chosen, cat, prefer1, prefer2, Collections.emptySet());
    }

    private void pickBest(List<ScoredItem> scored, List<WardrobeItem> all, Map<String, WardrobeItem> chosen,
                          String cat, String prefer1, String prefer2, Set<Long> excludeSet) {
        if (excludeSet != null && !excludeSet.isEmpty()) {
            Optional<WardrobeItem> freshScored = scored.stream()
                    .map(s -> s.item)
                    .filter(i -> cat.equals(i.getCategory()) && !excludeSet.contains(i.getId()))
                    .findFirst();
            if (freshScored.isPresent()) {
                chosen.put(cat, freshScored.get());
                return;
            }
            Optional<WardrobeItem> freshAll = all.stream()
                    .filter(i -> cat.equals(i.getCategory()) && !excludeSet.contains(i.getId()))
                    .findFirst();
            if (freshAll.isPresent()) {
                chosen.put(cat, freshAll.get());
                return;
            }
        }
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
                                 List<OutfitDto.MissingSlotVo> missingItems, String aiReasoning,
                                 double matchScore, boolean usedVisualReview) {
        StylistOutput(String themeTitle, List<Long> selectedItemIds,
                      List<OutfitDto.MissingSlotVo> missingItems, String aiReasoning, double matchScore) {
            this(themeTitle, selectedItemIds, missingItems, aiReasoning, matchScore, false);
        }
    }
}
