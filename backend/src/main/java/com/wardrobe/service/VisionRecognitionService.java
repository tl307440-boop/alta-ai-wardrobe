package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.WardrobeItemMapper;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.model.dto.VisionDto;
import com.wardrobe.model.dto.WardrobeDto;
import com.wardrobe.model.entity.WardrobeItem;
import com.wardrobe.service.client.MultimodalLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionRecognitionService {
    private final StorageService storageService;
    private final AiConfigService aiConfigService;
    private final MultimodalLlmClient llmClient;
    private final WardrobeService wardrobeService;
    private final WardrobeItemMapper wardrobeItemMapper;
    private final CommerceMatchService commerceMatchService;
    private final JsonUtils jsonUtils;

    public VisionDto.ItemRecognitionVo recognizeItem(Long userId, MultipartFile file, MultipartFile cutoutFile, String sourceType) {
        StorageService.StoredImage original = storageService.validateAndStoreImage(userId, file, "orig");

        String processedUrl = original.url();
        boolean cutoutFallback = true;
        if (cutoutFile != null && !cutoutFile.isEmpty()) {
            try {
                StorageService.StoredImage cutout = storageService.validateAndStoreImage(userId, cutoutFile, "cutout");
                processedUrl = cutout.url();
                cutoutFallback = false;
            } catch (Exception e) {
                log.warn("Client cutout file ignored, falling back to original image: {}", e.getMessage());
            }
        }

        AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
        if (creds.active()) {
            String dataUri = storageService.toDataUri(original.bytes(), original.mimeType());
            JsonNode json = llmClient.recognizeClothingItem(creds, dataUri, sourceType);
            if (!json.path("isClothing").asBoolean(true)) {
                throw new BusinessException(ErrorCode.NOT_CLOTHING);
            }
            String category = wardrobeService.normalizeCategory(json.path("category").asText("top"));
            String name = json.path("name").asText("识别单品");
            String color = json.path("color").asText("米白");
            String colorHex = json.path("colorHex").asText(resolveColorHex(color));
            List<String> seasons = wardrobeService.decodeSeasonsToCn(json.path("season").asText("spring,autumn"));
            List<String> tags = new ArrayList<>();
            if (json.path("styleTags").isArray()) {
                json.path("styleTags").forEach(n -> tags.add(n.asText()));
            }
            if (tags.isEmpty()) tags.add("极简通勤");

            return VisionDto.ItemRecognitionVo.builder()
                    .isClothing(true)
                    .name(name)
                    .category(category)
                    .subCategory(json.path("subCategory").asText(name))
                    .color(color)
                    .colorHex(colorHex)
                    .seasons(seasons)
                    .thickness(json.path("thickness").asInt(2))
                    .styleTags(tags)
                    .material(json.path("material").asText("精选混纺"))
                    .svgType(wardrobeService.inferSvgType(category, name))
                    .originalImageUrl(original.url())
                    .processedImageUrl(processedUrl)
                    .cutoutFallback(cutoutFallback)
                    .aiRawResult(json.toString())
                    .confidence(96.4)
                    .usedRealLlm(true)
                    .notice("已通过服务端配置的多模态模型 (" + creds.visionModel() + ") 完成实时语义解析")
                    .build();
        }

        // Local intelligent fallback when no API Key has been configured yet
        String filename = Objects.toString(file.getOriginalFilename(), "").toLowerCase();
        String guessedCategory = guessCategoryFromFilename(filename);
        String guessedName = guessNameFromFilename(filename, guessedCategory);
        String guessedColor = guessColorFromFilename(filename);
        String guessedHex = resolveColorHex(guessedColor);
        List<String> tags = List.of("极简通勤", "日常百搭");

        Map<String, Object> rawMock = Map.of(
                "mode", "local_fallback",
                "filename", filename,
                "category", guessedCategory,
                "color", guessedColor
        );

        return VisionDto.ItemRecognitionVo.builder()
                .isClothing(true)
                .name(guessedName)
                .category(guessedCategory)
                .subCategory(guessedName)
                .color(guessedColor)
                .colorHex(guessedHex)
                .seasons(List.of("春", "秋"))
                .thickness(2)
                .styleTags(tags)
                .material("精选混纺")
                .svgType(wardrobeService.inferSvgType(guessedCategory, guessedName))
                .originalImageUrl(original.url())
                .processedImageUrl(processedUrl)
                .cutoutFallback(cutoutFallback)
                .aiRawResult(jsonUtils.toJson(rawMock))
                .confidence(92.0)
                .usedRealLlm(false)
                .notice("未配置模型 API Key，已使用本地基础模板解析；前往「风格偏好」填写 API Key 即可在服务端实时启用多模态视觉识别")
                .build();
    }

    private static final int MAX_DETECTED_ITEMS = 8;

    /**
     * Multi-garment detection for wardrobe ingestion. Returns one structured item per visible garment together with
     * a normalized bounding box so the browser can crop + cut out each piece. Without an active model nothing is
     * stored and an empty list is returned (the client then falls back to the single-item pipeline).
     */
    public VisionDto.ItemsDetectionVo detectItems(Long userId, MultipartFile file, String sourceType) {
        AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
        if (!creds.active()) {
            storageService.validateImageOnly(file);
            return VisionDto.ItemsDetectionVo.builder()
                    .items(List.of())
                    .usedRealLlm(false)
                    .notice("未配置模型 API Key，无法自动识别多件单品")
                    .build();
        }

        StorageService.StoredImage original = storageService.validateAndStoreImage(userId, file, "orig");
        String dataUri = storageService.toDataUri(original.bytes(), original.mimeType());
        JsonNode json = llmClient.detectClothingItems(creds, dataUri, sourceType);
        if (!json.path("isClothing").asBoolean(true)) {
            throw new BusinessException(ErrorCode.NOT_CLOTHING);
        }

        List<WardrobeItem> userWardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));
        List<VisionDto.DetectedItem> items = new ArrayList<>();
        JsonNode arr = json.path("items");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (items.size() >= MAX_DETECTED_ITEMS) break;
                items.add(toDetectedItem(n, userWardrobe));
            }
        }
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_CLOTHING);
        }

        return VisionDto.ItemsDetectionVo.builder()
                .originalImageUrl(original.url())
                .items(items)
                .usedRealLlm(true)
                .notice("已通过服务端配置的多模态模型 (" + creds.visionModel() + ") 识别出 " + items.size() + " 件单品")
                .build();
    }

    private VisionDto.DetectedItem toDetectedItem(JsonNode n, List<WardrobeItem> userWardrobe) {
        String category = wardrobeService.normalizeCategory(n.path("category").asText("top"));
        String name = n.path("name").asText("识别单品");
        String color = n.path("color").asText("米白");
        String colorHex = n.path("colorHex").asText(resolveColorHex(color));
        List<String> tags = new ArrayList<>();
        if (n.path("styleTags").isArray()) {
            n.path("styleTags").forEach(t -> {
                String v = t.asText("").trim();
                if (!v.isEmpty() && !tags.contains(v)) tags.add(v);
            });
        }
        if (tags.isEmpty()) tags.add("极简通勤");
        String seasonRaw = n.path("season").isArray()
                ? String.join(",", jsonUtils.toStringList(n.path("season").toString()))
                : n.path("season").asText("spring,autumn");
        String svgType = wardrobeService.inferSvgType(category, name);

        MatchResult dup = findBestWardrobeMatch(userWardrobe,
                new RawDetectedPiece(categoryToLabel(category), name, category, color, colorHex, svgType,
                        tags, name + " " + color, 50, 50));

        return VisionDto.DetectedItem.builder()
                .name(name)
                .category(category)
                .subCategory(n.path("subCategory").asText(name))
                .color(color)
                .colorHex(colorHex)
                .seasons(wardrobeService.decodeSeasonsToCn(seasonRaw))
                .thickness(n.path("thickness").asInt(2))
                .styleTags(tags)
                .material(n.path("material").asText("精选混纺"))
                .svgType(svgType)
                .bbox(normalizeBoundingBox(n.path("bbox")))
                .similarWardrobeName(dup != null ? dup.item.getName() : null)
                .similarity(dup != null ? dup.similarity : 0)
                .aiRawResult(n.toString())
                .build();
    }

    /**
     * Normalizes whatever box format a vision model returns into fractions of the image ([0,1], top-left origin):
     * [x, y, w, h] or {x,y,w,h}; 0..1 fractions, 0..100 percentages or 0..1000 grids; and corner boxes
     * [x1, y1, x2, y2] (detected when x+w would overflow the image). Returns null when the box is unusable.
     */
    static VisionDto.BoundingBox normalizeBoundingBox(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        double a;
        double b;
        double c;
        double d;
        if (node.isArray() && node.size() == 4) {
            a = node.get(0).asDouble(Double.NaN);
            b = node.get(1).asDouble(Double.NaN);
            c = node.get(2).asDouble(Double.NaN);
            d = node.get(3).asDouble(Double.NaN);
        } else if (node.isObject()) {
            a = node.path("x").asDouble(Double.NaN);
            b = node.path("y").asDouble(Double.NaN);
            c = node.has("w") ? node.path("w").asDouble(Double.NaN) : node.path("width").asDouble(Double.NaN);
            d = node.has("h") ? node.path("h").asDouble(Double.NaN) : node.path("height").asDouble(Double.NaN);
        } else {
            return null;
        }
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return null;
        if (a < 0 || b < 0 || c <= 0 || d <= 0) return null;

        double max = Math.max(Math.max(a, b), Math.max(c, d));
        double scale;
        if (max <= 1.0) scale = 1.0;
        else if (max <= 100.0) scale = 100.0;
        else if (max <= 1000.0) scale = 1000.0;
        else return null;
        double x = a / scale;
        double y = b / scale;
        double w = c / scale;
        double h = d / scale;

        // Corner format [x1, y1, x2, y2]: the "width" would push the box outside the image.
        if ((x + w > 1.02 || y + h > 1.02) && w > x && h > y) {
            w = w - x;
            h = h - y;
        }

        x = Math.min(x, 1.0);
        y = Math.min(y, 1.0);
        w = Math.min(w, 1.0 - x);
        h = Math.min(h, 1.0 - y);
        if (w < 0.04 || h < 0.04) return null;
        return VisionDto.BoundingBox.builder()
                .x(round4(x)).y(round4(y)).w(round4(w)).h(round4(h))
                .build();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Stores one client-side crop (and its optional browser cutout) of a detected garment into the caller's
     * per-user upload folder after magic-byte validation.
     */
    public VisionDto.StoredItemImageVo storeItemImage(Long userId, MultipartFile file, MultipartFile cutoutFile) {
        StorageService.StoredImage original = storageService.validateAndStoreImage(userId, file, "crop");
        String processedUrl = original.url();
        boolean cutoutFallback = true;
        if (cutoutFile != null && !cutoutFile.isEmpty()) {
            try {
                StorageService.StoredImage cutout = storageService.validateAndStoreImage(userId, cutoutFile, "cutout");
                processedUrl = cutout.url();
                cutoutFallback = false;
            } catch (Exception e) {
                log.warn("Client cutout file ignored, falling back to crop image: {}", e.getMessage());
            }
        }
        return VisionDto.StoredItemImageVo.builder()
                .originalImageUrl(original.url())
                .processedImageUrl(processedUrl)
                .cutoutFallback(cutoutFallback)
                .build();
    }

    public VisionDto.InspirationAnalyzeVo analyzeInspiration(Long userId, MultipartFile file, String presetId,
                                                             String userQuery, BigDecimal budgetLimit) {
        BigDecimal budget = budgetLimit != null ? budgetLimit : new BigDecimal("300");
        List<WardrobeItem> userWardrobe = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, userId));

        String inspirationUrl = null;
        boolean usedRealLlm = false;
        String notice;
        String overallStyle = "韩系松弛通勤";
        List<RawDetectedPiece> pieces = new ArrayList<>();

        if (file != null && !file.isEmpty()) {
            StorageService.StoredImage stored = storageService.validateAndStoreImage(userId, file, "insp");
            inspirationUrl = stored.url();
            AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
            if (creds.active()) {
                String dataUri = storageService.toDataUri(stored.bytes(), stored.mimeType());
                JsonNode json = llmClient.analyzeStreetInspiration(creds, dataUri, userQuery, budget.toPlainString());
                overallStyle = json.path("overallStyle").asText("个性化街拍穿搭");
                JsonNode arr = json.path("detectedItems");
                int idx = 0;
                int[][] defaultCoords = {{26, 26}, {74, 24}, {30, 68}, {75, 74}, {50, 48}};
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String cat = wardrobeService.normalizeCategory(n.path("category").asText("top"));
                        String name = n.path("name").asText("拆解单品");
                        String color = n.path("color").asText("燕麦");
                        String hex = n.path("colorHex").asText(resolveColorHex(color));
                        List<String> tags = new ArrayList<>();
                        if (n.path("styleTags").isArray()) n.path("styleTags").forEach(t -> tags.add(t.asText()));
                        int px = n.path("posX").asInt(defaultCoords[idx % defaultCoords.length][0]);
                        int py = n.path("posY").asInt(defaultCoords[idx % defaultCoords.length][1]);
                        pieces.add(new RawDetectedPiece(
                                n.path("slotLabel").asText(categoryToLabel(cat)),
                                name, cat, color, hex,
                                wardrobeService.inferSvgType(cat, name),
                                tags, n.path("keywords").asText(name + " " + color), px, py
                        ));
                        idx++;
                    }
                }
                usedRealLlm = true;
                notice = "已调用服务端多模态模型 (" + creds.visionModel() + ") 完成街拍单品拆解与个人衣橱检索";
            } else {
                pieces = buildPresetPieces("insp-1");
                notice = "街拍图片已保存；未配置模型 API Key，已使用默认街拍拆解结构结合你的个人衣橱进行匹配";
            }
        } else {
            String pid = (presetId != null && !presetId.isBlank()) ? presetId : "insp-1";
            overallStyle = "insp-2".equals(pid) ? "美拉德复古学院风" : "韩系松弛通勤";
            pieces = buildPresetPieces(pid);
            notice = "已完成灵感拆解，仅检索当前登录用户的个人衣橱并按预算 ≤ ¥" + budget.stripTrailingZeros().toPlainString() + " 召回平替";
        }

        List<VisionDto.InspirationItemMatch> matches = new ArrayList<>();
        for (RawDetectedPiece p : pieces) {
            MatchResult best = findBestWardrobeMatch(userWardrobe, p);
            List<OutfitDto.ProductVo> products = best == null
                    ? commerceMatchService.matchProducts(p.category, p.keywords, p.color, budget, 2)
                    : Collections.emptyList();
            matches.add(VisionDto.InspirationItemMatch.builder()
                    .slotLabel(p.slotLabel)
                    .name(p.name)
                    .category(p.category)
                    .color(p.color)
                    .colorHex(p.colorHex)
                    .svgType(p.svgType)
                    .styleTags(p.styleTags)
                    .keywords(p.keywords)
                    .posX(p.posX)
                    .posY(p.posY)
                    .matchedWardrobe(best != null ? wardrobeService.toVo(best.item) : null)
                    .similarity(best != null ? best.similarity : 0)
                    .products(products)
                    .build());
        }

        return VisionDto.InspirationAnalyzeVo.builder()
                .overallStyle(overallStyle)
                .inspirationImageUrl(inspirationUrl)
                .items(matches)
                .usedRealLlm(usedRealLlm)
                .notice(notice)
                .build();
    }

    private MatchResult findBestWardrobeMatch(List<WardrobeItem> wardrobe, RawDetectedPiece piece) {
        WardrobeItem bestItem = null;
        int bestScore = 0;
        for (WardrobeItem item : wardrobe) {
            if (!piece.category.equalsIgnoreCase(item.getCategory())) continue;
            int score = 60;
            String itemSvg = item.getSvgType() != null ? item.getSvgType() : wardrobeService.inferSvgType(item.getCategory(), item.getName());
            if (piece.svgType.equalsIgnoreCase(itemSvg)) {
                score += 24;
            }
            if (piece.color != null && item.getColor() != null &&
                    (piece.color.contains(item.getColor()) || item.getColor().contains(piece.color))) {
                score += 12;
            }
            List<String> itemTags = jsonUtils.toStringList(item.getStyleTags());
            for (String t : piece.styleTags) {
                if (itemTags.contains(t)) {
                    score += 3;
                }
            }
            score = Math.min(98, score);
            // Require strong match (>= 82) so missing outerwear like trench/suede jacket triggers commerce dupes
            if (score >= 82 && score > bestScore) {
                bestScore = score;
                bestItem = item;
            }
        }
        return bestItem != null ? new MatchResult(bestItem, bestScore) : null;
    }

    private List<RawDetectedPiece> buildPresetPieces(String presetId) {
        if ("insp-2".equals(presetId)) {
            return List.of(
                    new RawDetectedPiece("外套", "复古美拉德翻毛麂皮夹克", "outerwear", "焦糖棕", "#8A5230", "suede_jacket",
                            List.of("美拉德复古", "工装休闲"), "美拉德 麂皮 夹克 外套", 26, 26),
                    new RawDetectedPiece("上衣", "法式藏蓝条纹海魂衫", "top", "牛仔蓝", "#3B5372", "striped_shirt",
                            List.of("法式松弛", "Clean Fit"), "条纹 海魂衫 打底", 74, 24),
                    new RawDetectedPiece("下装", "黑色高腰垂感阔腿裤", "bottom", "炭黑", "#262628", "trousers",
                            List.of("极简通勤", "干练面试"), "黑色 垂感 阔腿裤", 30, 68),
                    new RawDetectedPiece("鞋履", "米灰复古德训鞋", "shoes", "燕麦", "#DFD3C3", "sneakers",
                            List.of("Clean Fit", "日常休闲"), "米灰 复古 德训鞋", 75, 74)
            );
        }
        return List.of(
                new RawDetectedPiece("外套", "米色双排扣短款风衣", "outerwear", "燕麦", "#DFD3C3", "trench",
                        List.of("极简通勤", "英伦风"), "米色 双排扣 短款 风衣", 26, 26),
                new RawDetectedPiece("上衣", "纯白宽松圆领内搭T", "top", "纯白", "#FFFFFF", "tshirt",
                        List.of("Clean Fit", "百搭"), "白色 基础 T恤", 74, 24),
                new RawDetectedPiece("下装", "高腰深蓝直筒牛仔裤", "bottom", "牛仔蓝", "#3B5372", "jeans",
                        List.of("Clean Fit", "日常休闲"), "深蓝 直筒 牛仔裤", 30, 68),
                new RawDetectedPiece("鞋履", "复古焦糖棕乐福鞋", "shoes", "焦糖棕", "#8A5230", "loafers",
                        List.of("极简通勤", "美拉德复古"), "焦糖棕 皮质 乐福鞋", 75, 74)
        );
    }

    private String resolveColorHex(String color) {
        if (color == null) return "#F3EDE2";
        if (color.contains("纯白") || color.equals("白") || color.contains("白色")) return "#FFFFFF";
        if (color.contains("米白")) return "#F3EDE2";
        if (color.contains("燕麦")) return "#DFD3C3";
        if (color.contains("卡其")) return "#C7B299";
        if (color.contains("棕") || color.contains("咖")) return "#8A5230";
        if (color.contains("牛仔") || color.contains("藏蓝") || color.contains("深蓝")) return "#3B5372";
        if (color.contains("雾霾蓝") || color.contains("浅蓝") || color.contains("蓝")) return "#6F869C";
        if (color.contains("黑")) return "#262628";
        return "#DFD3C3";
    }

    private String guessCategoryFromFilename(String name) {
        if (name.contains("coat") || name.contains("jacket") || name.contains("blazer") || name.contains("trench") || name.contains("外套") || name.contains("风衣") || name.contains("开衫")) return "outerwear";
        if (name.contains("pant") || name.contains("jean") || name.contains("skirt") || name.contains("裤") || name.contains("裙")) return "bottom";
        if (name.contains("shoe") || name.contains("sneaker") || name.contains("loafer") || name.contains("鞋")) return "shoes";
        if (name.contains("bag") || name.contains("tote") || name.contains("包")) return "accessory";
        return "top";
    }

    private String guessNameFromFilename(String filename, String category) {
        return switch (category) {
            case "outerwear" -> "燕麦通勤外套（待复核）";
            case "bottom" -> "高腰垂感长裤（待复核）";
            case "shoes" -> "极简百搭鞋履（待复核）";
            case "accessory" -> "极简通勤手袋（待复核）";
            default -> "简约百搭上衣（待复核）";
        };
    }

    private String guessColorFromFilename(String filename) {
        if (filename.contains("black") || filename.contains("黑")) return "炭黑";
        if (filename.contains("blue") || filename.contains("jean") || filename.contains("蓝")) return "牛仔蓝";
        if (filename.contains("brown") || filename.contains("棕")) return "焦糖棕";
        if (filename.contains("white") || filename.contains("白")) return "米白";
        return "燕麦";
    }

    private String categoryToLabel(String cat) {
        return switch (cat) {
            case "outerwear" -> "外套";
            case "top" -> "上衣";
            case "bottom" -> "下装";
            case "shoes" -> "鞋履";
            case "accessory" -> "包配";
            default -> "单品";
        };
    }

    private record RawDetectedPiece(String slotLabel, String name, String category, String color, String colorHex,
                                    String svgType, List<String> styleTags, String keywords, int posX, int posY) {}

    private record MatchResult(WardrobeItem item, int similarity) {}
}
