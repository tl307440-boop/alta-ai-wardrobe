package com.wardrobe.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.model.dto.AiConfigDto;
import com.wardrobe.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultimodalLlmClient {
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    private final ObjectMapper objectMapper;
    private final AiConfigService aiConfigService;

    @Value("${app.ai.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${app.ai.read-timeout-ms:30000}")
    private long readTimeoutMs;

    @Value("${app.ai.image-timeout-ms:90000}")
    private long imageTimeoutMs;

    public record ReferenceImageInput(String filename, String mimeType, byte[] bytes) {}

    public record GeneratedImageResult(byte[] imageBytes, String remoteUrl, String endpointUsed) {}

    public AiConfigDto.TestResultVo testConnection(Long userId, AiConfigDto.TestRequest req) {
        AiConfigService.ResolvedAiCredentials saved = aiConfigService.resolveForUser(userId);
        String baseUrl = (req != null && req.getBaseUrl() != null && !req.getBaseUrl().isBlank())
                ? trimSlash(req.getBaseUrl()) : saved.baseUrl();
        String apiKey = (req != null && req.getApiKey() != null && !req.getApiKey().isBlank())
                ? req.getApiKey().trim() : saved.apiKey();
        String model = (req != null && req.getChatModel() != null && !req.getChatModel().isBlank())
                ? req.getChatModel().trim() : saved.chatModel();

        if (apiKey == null || apiKey.isBlank()) {
            return AiConfigDto.TestResultVo.builder()
                    .success(false)
                    .latencyMs(0)
                    .message("尚未填写 API Key，请先输入后再测试连通性")
                    .build();
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 16);
            body.put("temperature", 0);
            body.put("messages", List.of(
                    Map.of("role", "user", "content", "Reply with OK only.")
            ));
            String content = sendChatCompletion(baseUrl, apiKey, body, 12000);
            long latency = System.currentTimeMillis() - start;
            return AiConfigDto.TestResultVo.builder()
                    .success(true)
                    .latencyMs(latency)
                    .modelReply(content != null ? content.trim() : "OK")
                    .message("服务端连通成功，模型已实时生效")
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return AiConfigDto.TestResultVo.builder()
                    .success(false)
                    .latencyMs(latency)
                    .message("连通性测试失败：" + e.getMessage())
                    .build();
        }
    }

    public JsonNode recognizeClothingItem(AiConfigService.ResolvedAiCredentials creds, String imageDataUri, String sourceType) {
        String systemPrompt = """
                你是专业服装多模态识别专家。请分析上传的衣物实物图或电商订单截图，仅返回严格合法的 JSON 对象，禁止输出 Markdown 代码围栏或额外说明文字。
                字段规范：
                - isClothing: boolean（如果不是服装/鞋靴/包袋配饰，例如风景、宠物、纯文档，必须返回 false）
                - name: string（中文单品名称，如"米白针织开衫"）
                - category: string（仅允许 outerwear / top / dress / bottom / shoes / bag / accessory；其中 dress 为连衣裙/连体裤/JSK，bag 为包袋，accessory 为丝袜/堆堆袜/首饰/帽子/围巾/墨镜/腰带等配饰）
                - subCategory: string（细分款式，如"针织开衫"、"链条包"、"过膝丝袜"）
                - color: string（优先映射到：米白/纯白/燕麦/卡其/焦糖棕/牛仔蓝/雾霾蓝/炭黑 或常见中文颜色）
                - colorHex: string（对应 HEX 色值，如 "#F3EDE2"）
                - season: string（逗号分隔：spring,summer,autumn,winter）
                - thickness: integer（1薄夏 / 2适中春秋 / 3厚冬）
                - styleTags: string[]（2~4个中文风格标签，如 ["温柔韩系","极简通勤"]）
                - material: string（材质，如"纯棉"、"羊毛混纺"）
                """;
        String userText = "screenshot".equalsIgnoreCase(sourceType)
                ? "这是一张电商商品/订单截图，请综合图片主体与截图文字识别服装单品并输出 JSON。"
                : "这是一张衣物实物图，请忽略背景杂物，精准提取服装单品结构化属性并输出 JSON。";

        return callVisionForJson(creds, systemPrompt, userText, imageDataUri);
    }

    /**
     * Multi-garment detection for wardrobe ingestion: one photo (e.g. a full outfit) in, N structured items
     * with a normalized bounding box each out, so the client can crop and cut out every piece individually.
     */
    public JsonNode detectClothingItems(AiConfigService.ResolvedAiCredentials creds, String imageDataUri, String sourceType) {
        String systemPrompt = """
                你是专业服装多模态识别专家。请识别图片中所有彼此独立、清晰可见的服装 / 鞋靴 / 包袋配饰单品（例如一张穿搭照里的上衣、裤子、鞋子），逐件输出结构化属性与位置框，仅返回严格合法的 JSON 对象，禁止输出 Markdown 代码围栏或额外说明文字：
                {
                  "isClothing": true,
                  "items": [
                    {
                      "name": "米白针织开衫",
                      "category": "outerwear",
                      "subCategory": "针织开衫",
                      "color": "米白",
                      "colorHex": "#F3EDE2",
                      "season": "spring,autumn",
                      "thickness": 2,
                      "styleTags": ["温柔韩系", "极简通勤"],
                      "material": "羊毛混纺",
                      "bbox": [0.18, 0.12, 0.64, 0.38]
                    }
                  ]
                }
                规则：
                - category 仅允许 outerwear / top / dress / bottom / shoes / bag / accessory（dress 为连衣裙/连体裤/JSK，bag 为包袋，accessory 为丝袜/堆堆袜/首饰/帽子/围巾/墨镜/腰带等配饰）；season 为逗号分隔的 spring,summer,autumn,winter；thickness 为 1 薄夏 / 2 适中春秋 / 3 厚冬；styleTags 为 2~4 个中文风格标签。
                - 只列真实可见的单品，不要臆造；完全被遮挡的不要列；图中只有一件就只返回一件；最多 8 件。
                - bbox 是该单品在整张图片中的紧致外接框，四个数都是 0~1 的小数：[x, y, w, h]，x、y 为框左上角相对图片宽、高的比例，w、h 为框宽、高占图片宽、高的比例。
                - 如果图片不是服装（风景、宠物、纯文档等），返回 {"isClothing": false, "items": []}。
                """;
        String userText = "screenshot".equalsIgnoreCase(sourceType)
                ? "这是一张电商商品/订单截图，请综合图片主体与截图文字，逐件识别服装单品并输出 JSON。"
                : "这是一张衣物或穿搭实物图，请忽略背景杂物，逐件识别其中的服装单品、给出每件的位置框并输出 JSON。";
        return callVisionForJson(creds, systemPrompt, userText, imageDataUri);
    }

    public JsonNode analyzeStreetInspiration(AiConfigService.ResolvedAiCredentials creds, String imageDataUri,
                                             String userQuery, String budgetStr) {
        String systemPrompt = """
                你是专业时尚穿搭拆解专家。请识别全身街拍/穿搭灵感图中的 3~5 件核心单品（外套、上衣、连衣裙、下装、鞋履、包袋、配饰），仅返回合法 JSON，禁止输出 Markdown 围栏或多余文字：
                {
                  "overallStyle": "韩系松弛通勤",
                  "detectedItems": [
                    {
                      "slotLabel": "外套",
                      "category": "outerwear",
                      "name": "米色双排扣短款风衣",
                      "color": "燕麦",
                      "colorHex": "#DFD3C3",
                      "styleTags": ["极简通勤", "英伦风"],
                      "keywords": "米色 短款 双排扣 风衣",
                      "posX": 26,
                      "posY": 28
                    }
                  ]
                }
                category 仅允许 outerwear / top / dress / bottom / shoes / bag / accessory；posX 和 posY 为 15~85 的百分比定位坐标。
                """;
        String userText = "用户需求：" + (userQuery != null && !userQuery.isBlank() ? userQuery : "拆解全身搭配单品")
                + "；单品预算上限：" + budgetStr + "元。请拆解图中单品并返回 JSON。";
        return callVisionForJson(creds, systemPrompt, userText, imageDataUri);
    }

    public JsonNode curateOutfitPlan(AiConfigService.ResolvedAiCredentials creds, String stylistUserPrompt) {
        String systemPrompt = """
                你是高级杂志风私人 AI 造型师。请基于提供的实时天气、场景、用户偏好、锁定锚点及候选衣橱 JSON，优先复用用户已有衣物完成全身搭配，仅返回合法 JSON，禁止输出 Markdown 围栏或多余说明：
                {
                  "themeTitle": "温柔通勤 · 轻松出门",
                  "selectedItemIds": [2, 4, 7, 9],
                  "missingItems": [
                    {
                      "category": "outerwear",
                      "name": "米色短款双排扣风衣",
                      "color": "燕麦",
                      "colorHex": "#DFD3C3",
                      "keywords": "米色 短款 风衣 通勤",
                      "reason": "当前微凉天气建议外搭短款风衣提升层次与腰线"
                    }
                  ],
                  "aiReasoning": "具体说明色彩呼应、温度适穿性与场景契合度（60~110字中文）",
                  "matchScore": 96.0
                }
                造型原则与硬性要求：
                1. selectedItemIds 只能从候选衣橱的真实 id 中挑选，每个品类（outerwear / top / dress / bottom / shoes / bag / accessory）最多选 1 件；其中 dress（连衣裙/连体裤/JSK，one_piece_dress=true）与 top、bottom 严格互斥；bag（包袋）与 accessory（丝袜/堆堆袜/首饰等配饰）为独立槽位，可按场景同时搭配（例如 JK 短裙 + 黑丝/堆堆袜 + 包袋）；
                2. 严格响应用户的「自然语言诉求」与审美风格，绝对禁止风格冲突（例如「可爱萝莉/甜美」严禁挑选暗黑哥特或辣妹微喇裤，「职场通勤」严禁挑选哥特/亮片礼服或渔网黑丝）；
                3. 单一视觉焦点：整套最多 1 件强印花/亮色/强肌理单品作主角，其余单品做克制衬托；
                4. 廓形重量平衡：下装宽松或垂坠（阔腿/微喇/伞裙）时配利落上衣；厚重外搭里配干净极简内搭；
                5. 物理叠穿合理并兼顾轮换多样性：优先同色/邻近色或一处可控对比，避免每次只选黑白灰基础款；若衣橱已有合适外套则 missingItems 可为空数组 []。
                """;

        return callTextForJson(creds, systemPrompt, stylistUserPrompt);
    }

    /**
     * Visual Contact-Sheet outfit curation: sends the numbered 12-tile garment grid alongside
     * structured wardrobe metadata so the VLM visually evaluates fabrics, prints, proportions, and colors.
     */
    public JsonNode curateOutfitPlanWithContactSheet(AiConfigService.ResolvedAiCredentials creds,
                                                     String stylistUserPrompt,
                                                     String contactSheetDataUri) {
        String systemPrompt = """
                你是高级杂志风私人多模态 AI 造型师。你将同时收到一张用户真实衣橱单品的【视觉联络表 Contact Sheet（最多 12 件高清格，左上角标注 #1..#N 与下方 ID）】以及文字元数据。
                请务必**先看图审视每件衣服的真实版型、材质肌理、印花图案与色彩深浅**，再结合天气、场景、身形与个人喜好挑出最和谐的全身搭配，仅返回合法 JSON，禁止输出 Markdown 围栏或多余说明：
                {
                  "themeTitle": "温柔通勤 · 轻松出门",
                  "selectedItemIds": [2, 4, 7, 9],
                  "missingItems": [
                    {
                      "category": "outerwear",
                      "name": "米色短款双排扣风衣",
                      "color": "燕麦",
                      "colorHex": "#DFD3C3",
                      "keywords": "米色 短款 风衣 通勤",
                      "reason": "当前微凉天气建议外搭短款风衣提升层次与腰线"
                    }
                  ],
                  "aiReasoning": "结合画面中观察到的真实面料/版型/色彩深浅，具体说明搭配理由与比例亮点（70~120字中文）",
                  "matchScore": 96.0
                }
                造型原则与硬性规则：
                1. selectedItemIds 只能从候选衣橱的真实数字 id 中挑选，每个品类（outerwear/top/dress/bottom/shoes/bag/accessory）最多选 1 件；bag（包袋）与 accessory（丝袜/堆堆袜/首饰等配饰）为独立槽位，可按风格同时入选；
                2. 严禁同时选择 ONE-PIECE/连衣裙/JSK(category=dress 或 one_piece_dress=true) 与任何上装（TOP）或独立下装（BOTTOM 裤装或半身裙），严禁风格/明暗/印花冲突（例如「可爱萝莉/甜美」严禁选暗黑哥特或辣妹微喇裤，「通勤/面试」严禁选黑丝/渔网袜/堆堆袜）或面料季节冲突；
                3. 单一视觉焦点：一套最多 1 件强印花/亮色/特殊材质主角单品，其余用低饱和净色衬托，保留至多 1 处可控冷暖对比；
                4. 廓形重量平衡与叠穿物理合理性：下装宽松垂坠时上装简洁利落；厚重外搭里配极简内搭且不能穿帮或相互掩盖；
                5. 轮换多样性：不偷懒只挑黑白灰基础款，主动结合图中特色单品打造质感造型；若衣橱已有合适外套则 missingItems 可为空数组 []。
                """;
        return callVisionForJson(creds, systemPrompt, stylistUserPrompt, contactSheetDataUri);
    }

    /**
     * Visual reverse captioning for Try-On Grounding: inspects all real garment cutouts/photos together
     * so downstream image generation receives exact collar, cut, fabric, hardware, and shade details.
     */
    public JsonNode extractOutfitVisualDetails(AiConfigService.ResolvedAiCredentials creds,
                                               String slotsMetadataText,
                                               List<String> garmentImageDataUris) {
        if (garmentImageDataUris == null || garmentImageDataUris.isEmpty()) {
            return null;
        }
        String systemPrompt = """
                你是资深高奢时装视觉总监。你将收到当前整套穿搭中每一件真实单品的图片（按用户提示中的 [图1]、[图2]... 顺序排列）及其名称品类。
                请严格**基于图片中实际可见的服装细节**进行结构化视觉特征反推，禁止凭空编造图中不存在的图案、文字或款式，仅返回合法 JSON，禁止输出 Markdown 围栏：
                {
                  "overallPalette": "燕麦浅米与复古深靛蓝形成柔和冷暖对比，哑光尼龙搭配重磅纯棉层次感清晰",
                  "items": [
                    {
                      "slotKey": "outerwear",
                      "zhSummary": "立领落肩短款夹克 · 哑光浅燕麦色防风面料 · 金属拉链与斜插袋设计",
                      "enPrompt": "cropped drop-shoulder jacket in matte light oatmeal beige technical fabric with stand collar, concealed front zip, and angled welt pockets"
                    }
                  ]
                }
                要求：
                1. items 数组必须覆盖传入列表里的每一件单品并严格保留对应的 slotKey；
                2. zhSummary 用 14~32 字中文精确概括该单品图上的真实廓形、领型、材质肌理、扣饰/口袋与真实色偏；
                3. enPrompt 用 18~36 个英文单词精准描述图中这件衣服的真实版型、领口、袖型、衣长/裤长、面料纹理、五金/扣子与色彩深浅，专用于高还原度生图模型提示词；
                4. overallPalette 用 16~34 字中文总结整套单品的色彩与材质协同度。
                """;
        String userText = "请按图片顺序逐件提取以下穿搭单品的真实视觉细节：\n" + slotsMetadataText;
        return callVisionForJson(creds, systemPrompt, userText, garmentImageDataUris);
    }

    /**
     * Converts raw garment image bytes to a compact data URI suitable for VLM inspection.
     * Large PNG/JPGs are resized to <= 512px JPEG; compact WebP files are passed through directly.
     */
    public String toCompactVisionDataUri(String filename, String mimeType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String safeMime = (mimeType != null && !mimeType.isBlank()) ? mimeType : "image/jpeg";
        if (!"image/webp".equalsIgnoreCase(safeMime) && bytes.length > 140 * 1024) {
            ReferenceImageInput compressed = compressReferenceImage(new ReferenceImageInput(filename, safeMime, bytes), 512);
            return "data:" + compressed.mimeType() + ";base64," + Base64.getEncoder().encodeToString(compressed.bytes());
        }
        return "data:" + safeMime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Post-generation VLM quality gate for Virtual Try-On results: compares the generated try-on image
     * against the original model photo and garment references to detect drift, swapped clothes, or artifacts.
     */
    public JsonNode verifyTryOnQuality(AiConfigService.ResolvedAiCredentials creds,
                                       String checklistText,
                                       List<String> imageDataUris) {
        if (creds == null || !creds.active()
                || creds.visionModel() == null || creds.visionModel().isBlank()
                || imageDataUris == null || imageDataUris.size() < 2) {
            return null;
        }
        String systemPrompt = """
                你是严格的虚拟试衣（VTON）视觉质检总监。你会收到一组参考图与最后一张【AI 生成试穿成图】：
                - 通常 [图1] 为原模特参考图，[中间图] 为真实衣物单品原图，[最后一张图] 为需要验收的 AI 生成试穿效果图。
                请对照原图从以下 4 项严格核查成图质量，仅返回合法 JSON，禁止输出 Markdown 围栏：
                {
                  "passed": true,
                  "score": 90,
                  "summary": "18~38字中文质检总结，写明人物一致性与单品还原度",
                  "issues": [],
                  "correctionHint": ""
                }
                核查标准：
                1. 人物面部五官、发型与头身比例是否与[图1]自然一致（无明显换脸、多肢体或面部崩坏）；
                2. 核心单品（上衣/外套/下装/连衣裙等）是否完整穿在身上，颜色、领型、版型、开合方式（拉链/纽扣）是否忠实还原未篡改；
                3. 全身构图自然完整（无异常截头/截脚或服饰融合穿帮）；
                4. 画面干净无乱码文字、拼贴边框或水印。
                判定要求：
                - score 范围 0~100；存在明显单品穿错、颜色严重偏差、擅改款式、畸形或人脸严重漂移时，必须令 passed=false、score < 72，并在 issues 列出具体问题、在 correctionHint 给出简明重画修正指令（中文 15~45 字）。
                """;
        return callVisionForJson(creds, systemPrompt, checklistText, imageDataUris);
    }

    /**
     * Visual AI Outfit Critique ("AI 搭配体检"): evaluates either a flat-lay collage canvas or a real mirror OOTD photo.
     */
    public JsonNode critiqueOutfitVisual(AiConfigService.ResolvedAiCredentials creds,
                                         String imageDataUri,
                                         String critiqueUserPrompt) {
        String systemPrompt = """
                你是专业且真诚的私人时尚造型顾问，正在为用户做「AI 搭配体检」。请仔细看图（可能是平铺拼贴方案，也可能是真人对镜 OOTD 实拍），结合用户身形、四季色彩、场景与天气，给出客观具体的视觉点评与 1 条可执行改进建议。仅返回合法 JSON，禁止输出 Markdown 围栏：
                {
                  "overallScore": 88,
                  "colorScore": 91,
                  "proportionScore": 85,
                  "sceneScore": 89,
                  "headline": "一句话精准总评（15~28字，不空洞吹捧）",
                  "highlights": [
                    "视觉亮点 1（具体到色彩/材质/层次）",
                    "视觉亮点 2"
                  ],
                  "improvements": [
                    "可立刻落地的改进建议 1（如塞衣角、挽袖口、调整鞋包呼应或替换单品）"
                  ],
                  "suggestedSwap": {
                    "itemId": 6,
                    "itemName": "卡其高腰百褶半身裙",
                    "category": "bottom",
                    "reason": "用你衣橱里这件替换后，腰线更清晰且与上衣色温更统一"
                  }
                }
                规则：
                - 各项分数范围 55~99，必须有真实区分度，不要全部给 95+；
                - suggestedSwap.itemId 必须严格来自提示词给出的【用户衣橱可替换候选列表】中的真实数字 id；若无需替换或列表为空，可将 suggestedSwap 设为 null。
                """;
        return callVisionForJson(creds, systemPrompt, critiqueUserPrompt, imageDataUri);
    }

    /**
     * Generates an editorial try-on preview via OpenAI-compatible relay endpoints.
     * Optimized to avoid reverse-proxy/CDN idle timeouts (e.g. 15s HTTP 524) using:
     * 1. Automatic reference image downscaling + JPEG compression (smaller upload & fewer vision tokens).
     * 2. Fast-mode defaults (size=1024x1024, quality=low) + SSE streaming (stream=true, partial_images=1).
     * 3. Optional /chat/completions streaming mode (stream=true) for relays that stream images via Chat SSE.
     */
    public GeneratedImageResult generateEditorialTryOn(AiConfigService.ResolvedAiCredentials creds,
                                                       String prompt,
                                                       List<ReferenceImageInput> referenceImages) throws Exception {
        return generateEditorialTryOn(creds, prompt, referenceImages, "auto");
    }

    public GeneratedImageResult generateEditorialTryOn(AiConfigService.ResolvedAiCredentials creds,
                                                       String prompt,
                                                       List<ReferenceImageInput> referenceImages,
                                                       String generationMode) throws Exception {
        String model = (creds.imageModel() != null && !creds.imageModel().isBlank())
                ? creds.imageModel().trim() : "gpt-image-1";
        String mode = (generationMode != null && !generationMode.isBlank())
                ? generationMode.trim().toLowerCase(Locale.ROOT) : "auto";

        boolean hdMode = "hd".equals(mode);
        int maxEdge = hdMode ? 768 : 512;
        List<ReferenceImageInput> optimizedRefs = new ArrayList<>();
        if (referenceImages != null) {
            for (ReferenceImageInput ref : referenceImages) {
                optimizedRefs.add(compressReferenceImage(ref, maxEdge));
            }
        }

        if ("chat_stream".equals(mode)) {
            return sendChatStreamImage(creds.baseUrl(), creds.apiKey(), model, prompt, optimizedRefs);
        }

        String size = hdMode
                ? (model.toLowerCase(Locale.ROOT).contains("dall-e") ? "1024x1792" : "1024x1536")
                : "1024x1024";
        String quality = hdMode ? null : (model.toLowerCase(Locale.ROOT).contains("dall-e") ? "standard" : "low");

        if (!optimizedRefs.isEmpty()) {
            try {
                return sendImagesEditsSmart(creds.baseUrl(), creds.apiKey(), model, prompt, optimizedRefs, size, quality);
            } catch (Exception e) {
                String msg = Objects.toString(e.getMessage(), "");
                // Never trigger a duplicate paid /images/generations call when upstream timed out (524/504/408)
                if (isTimeoutError(e, msg)) {
                    throw e;
                }
                log.warn("Upstream /images/edits unavailable ({}), falling back to /images/generations", msg);
            }
        }
        return sendImagesGenerationsSmart(creds.baseUrl(), creds.apiKey(), model, prompt, size, quality);
    }

    private boolean isTimeoutError(Exception e, String msg) {
        return msg.contains("HTTP 524") || msg.contains("HTTP 504") || msg.contains("HTTP 408")
                || e instanceof java.net.http.HttpTimeoutException;
    }

    private ReferenceImageInput compressReferenceImage(ReferenceImageInput input, int maxEdge) {
        if (input == null || input.bytes() == null || input.bytes().length == 0) {
            return input;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(input.bytes()));
            if (src == null) {
                return input;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int longest = Math.max(w, h);
            if (longest <= maxEdge && input.bytes().length <= 80 * 1024 && "image/jpeg".equalsIgnoreCase(input.mimeType())) {
                return input;
            }
            double scale = longest > maxEdge ? (double) maxEdge / longest : 1.0;
            int nw = Math.max(64, (int) Math.round(w * scale));
            int nh = Math.max(64, (int) Math.round(h * scale));

            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                    writer.setOutput(ios);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(0.84f);
                    }
                    writer.write(null, new IIOImage(dst, null, null), param);
                } finally {
                    writer.dispose();
                }
            } else {
                ImageIO.write(dst, "jpg", out);
            }
            byte[] compressed = out.toByteArray();
            if (compressed.length > 512 && compressed.length < input.bytes().length) {
                String baseName = input.filename().replaceAll("\\.[^.]+$", "") + ".jpg";
                log.info("Compressed try-on reference {} ({}x{} {}KB -> {}x{} {}KB)",
                        input.filename(), w, h, input.bytes().length / 1024, nw, nh, compressed.length / 1024);
                return new ReferenceImageInput(baseName, "image/jpeg", compressed);
            }
        } catch (Exception e) {
            log.debug("Reference image compression skipped for {}: {}", input.filename(), e.getMessage());
        }
        return input;
    }

    private GeneratedImageResult sendImagesEditsSmart(String baseUrl, String apiKey, String model, String prompt,
                                                      List<ReferenceImageInput> images, String size, String quality) throws Exception {
        // Attempt 1: try streaming SSE (stream=true, partial_images=1) + fast quality/size so proxies see early bytes
        long t0 = System.currentTimeMillis();
        try {
            return sendImagesEdits(baseUrl, apiKey, model, prompt, images, size, quality, true);
        } catch (Exception firstErr) {
            long elapsed = System.currentTimeMillis() - t0;
            String msg = Objects.toString(firstErr.getMessage(), "");
            if (isTimeoutError(firstErr, msg)) {
                throw firstErr;
            }
            // Fast protocol/param rejection (< 10s, non-timeout): retry without stream/partial_images
            if (elapsed < 10_000) {
                log.warn("Upstream /images/edits stream attempt failed in {}ms ({}), retrying non-stream", elapsed, msg);
                String retryQuality = (msg.toLowerCase(Locale.ROOT).contains("quality")) ? null : quality;
                try {
                    return sendImagesEdits(baseUrl, apiKey, model, prompt, images, size, retryQuality, false);
                } catch (Exception secondErr) {
                    String msg2 = Objects.toString(secondErr.getMessage(), "");
                    if (!isTimeoutError(secondErr, msg2) && retryQuality != null && msg2.contains("HTTP 400")) {
                        log.warn("Upstream /images/edits rejected quality={} ({}), retrying default quality", retryQuality, msg2);
                        return sendImagesEdits(baseUrl, apiKey, model, prompt, images, "1024x1024", null, false);
                    }
                    throw secondErr;
                }
            }
            throw firstErr;
        }
    }

    private GeneratedImageResult sendImagesEdits(String baseUrl, String apiKey, String model, String prompt,
                                                 List<ReferenceImageInput> images, String size, String quality,
                                                 boolean stream) throws Exception {
        String endpoint = trimSlash(baseUrl) + "/images/edits";
        String boundary = "----AltaWardrobeBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeFormField(baos, boundary, "model", model);
        writeFormField(baos, boundary, "prompt", prompt);
        writeFormField(baos, boundary, "size", size);
        if (quality != null && !quality.isBlank()) {
            writeFormField(baos, boundary, "quality", quality);
        }
        if (stream) {
            writeFormField(baos, boundary, "stream", "true");
            writeFormField(baos, boundary, "partial_images", "1");
        }

        String fieldName = images.size() > 1 ? "image[]" : "image";
        for (ReferenceImageInput img : images) {
            baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + img.filename() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Type: " + img.mimeType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(img.bytes());
            baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(imageTimeoutMs))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey);
        if (stream) {
            reqBuilder.header("Accept", "text/event-stream, application/json");
        }
        HttpRequest request = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        long ttfb = System.currentTimeMillis() - start;
        log.info("Upstream /images/edits (stream={}, size={}, quality={}) HTTP {} Content-Type={} TTFB={}ms",
                stream, size, quality, response.statusCode(), contentType, ttfb);

        return handleImageStreamOrJsonResponse(response, contentType, stream ? "/images/edits (stream)" : "/images/edits");
    }

    private GeneratedImageResult sendImagesGenerationsSmart(String baseUrl, String apiKey, String model, String prompt,
                                                            String size, String quality) throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            return sendImagesGenerations(baseUrl, apiKey, model, prompt, size, quality, true);
        } catch (Exception firstErr) {
            long elapsed = System.currentTimeMillis() - t0;
            String msg = Objects.toString(firstErr.getMessage(), "");
            if (isTimeoutError(firstErr, msg)) {
                throw firstErr;
            }
            if (elapsed < 10_000) {
                log.warn("Upstream /images/generations stream attempt failed in {}ms ({}), retrying non-stream", elapsed, msg);
                String retryQuality = (msg.toLowerCase(Locale.ROOT).contains("quality")) ? null : quality;
                try {
                    return sendImagesGenerations(baseUrl, apiKey, model, prompt, size, retryQuality, false);
                } catch (Exception secondErr) {
                    String msg2 = Objects.toString(secondErr.getMessage(), "");
                    if (!isTimeoutError(secondErr, msg2) && retryQuality != null && msg2.contains("HTTP 400")) {
                        return sendImagesGenerations(baseUrl, apiKey, model, prompt, "1024x1024", null, false);
                    }
                    throw secondErr;
                }
            }
            throw firstErr;
        }
    }

    private GeneratedImageResult sendImagesGenerations(String baseUrl, String apiKey, String model, String prompt,
                                                       String size, String quality, boolean stream) throws Exception {
        String endpoint = trimSlash(baseUrl) + "/images/generations";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        if (quality != null && !quality.isBlank()) {
            body.put("quality", quality);
        }
        if (stream) {
            body.put("stream", true);
            body.put("partial_images", 1);
        }

        String payload = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(imageTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
        if (stream) {
            reqBuilder.header("Accept", "text/event-stream, application/json");
        }
        HttpRequest request = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        long ttfb = System.currentTimeMillis() - start;
        log.info("Upstream /images/generations (stream={}, size={}, quality={}) HTTP {} Content-Type={} TTFB={}ms",
                stream, size, quality, response.statusCode(), contentType, ttfb);

        return handleImageStreamOrJsonResponse(response, contentType, stream ? "/images/generations (stream)" : "/images/generations");
    }

    private GeneratedImageResult sendChatStreamImage(String baseUrl, String apiKey, String model, String prompt,
                                                     List<ReferenceImageInput> images) throws Exception {
        String endpoint = trimSlash(baseUrl) + "/chat/completions";
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", prompt));
        if (images != null) {
            for (ReferenceImageInput img : images) {
                String dataUri = "data:" + img.mimeType() + ";base64," + Base64.getEncoder().encodeToString(img.bytes());
                userContent.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", dataUri, "detail", "low")
                ));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", List.of(
                Map.of("role", "user", "content", userContent)
        ));

        String payload = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(imageTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        long ttfb = System.currentTimeMillis() - start;
        log.info("Upstream /chat/completions (stream=true, model={}) HTTP {} Content-Type={} TTFB={}ms",
                model, response.statusCode(), contentType, ttfb);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errBody = readStreamToString(response.body());
            String snippet = errBody.length() > 240 ? errBody.substring(0, 240) : errBody;
            throw new IllegalStateException("HTTP " + response.statusCode() + " - " + snippet);
        }

        StringBuilder fullText = new StringBuilder();
        String extractedB64 = null;
        String extractedUrl = null;
        StringBuilder rawAll = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawAll.append(line).append('\n');
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choice = chunk.path("choices").path(0);
                    JsonNode delta = choice.has("delta") ? choice.path("delta") : choice.path("message");
                    // 1. delta.images / message.images (OpenRouter / Gemini / New API multimodal stream)
                    JsonNode imagesNode = delta.path("images");
                    if (imagesNode.isArray() && !imagesNode.isEmpty()) {
                        JsonNode img0 = imagesNode.get(0);
                        String imgUrl = img0.path("image_url").path("url").asText(img0.path("url").asText(""));
                        String b64 = img0.path("b64_json").asText("");
                        if (!b64.isBlank()) extractedB64 = b64;
                        else if (!imgUrl.isBlank()) extractedUrl = imgUrl;
                    }
                    // 2. delta.content (string or structured content parts)
                    JsonNode contentNode = delta.path("content");
                    if (contentNode.isTextual()) {
                        fullText.append(contentNode.asText());
                    } else if (contentNode.isArray()) {
                        for (JsonNode part : contentNode) {
                            if (part.has("text")) fullText.append(part.path("text").asText(""));
                            String pUrl = part.path("image_url").path("url").asText("");
                            if (!pUrl.isBlank()) extractedUrl = pUrl;
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore heartbeat or partial non-JSON lines
                }
            }
        }

        // If server returned non-SSE JSON despite stream=true
        if (extractedB64 == null && extractedUrl == null && fullText.isEmpty() && rawAll.toString().trim().startsWith("{")) {
            JsonNode root = objectMapper.readTree(rawAll.toString());
            JsonNode msg = root.path("choices").path(0).path("message");
            if (msg.path("content").isTextual()) {
                fullText.append(msg.path("content").asText());
            }
            JsonNode imagesNode = msg.path("images");
            if (imagesNode.isArray() && !imagesNode.isEmpty()) {
                String imgUrl = imagesNode.get(0).path("image_url").path("url").asText("");
                if (!imgUrl.isBlank()) extractedUrl = imgUrl;
            }
        }

        if (extractedB64 != null && !extractedB64.isBlank()) {
            return new GeneratedImageResult(Base64.getDecoder().decode(extractedB64), null, "/chat/completions (stream)");
        }
        if (extractedUrl != null && !extractedUrl.isBlank()) {
            return resolveImageFromUrlOrDataUri(extractedUrl, "/chat/completions (stream)");
        }

        String text = fullText.toString();
        Matcher dataUriMatcher = Pattern.compile("data:image/(?:png|jpeg|jpg|webp);base64,([A-Za-z0-9+/=\\r\\n]+)").matcher(text);
        if (dataUriMatcher.find()) {
            String cleanB64 = dataUriMatcher.group(1).replaceAll("\\s+", "");
            return new GeneratedImageResult(Base64.getDecoder().decode(cleanB64), null, "/chat/completions (stream)");
        }
        Matcher mdImgMatcher = Pattern.compile("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)").matcher(text);
        if (mdImgMatcher.find()) {
            return resolveImageFromUrlOrDataUri(mdImgMatcher.group(1), "/chat/completions (stream)");
        }
        Matcher rawUrlMatcher = Pattern.compile("(https?://[^\\s\"'<>]+?\\.(?:png|jpg|jpeg|webp)(?:\\?[^\\s\"'<>]*)?)").matcher(text);
        if (rawUrlMatcher.find()) {
            return resolveImageFromUrlOrDataUri(rawUrlMatcher.group(1), "/chat/completions (stream)");
        }
        throw new IllegalStateException("流式对话未返回可解析的图片数据（片段："
                + (text.length() > 140 ? text.substring(0, 140) : text) + "）");
    }

    private GeneratedImageResult handleImageStreamOrJsonResponse(HttpResponse<InputStream> response,
                                                                 String contentType,
                                                                 String endpointUsed) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errBody = readStreamToString(response.body());
            String snippet = errBody.length() > 240 ? errBody.substring(0, 240) : errBody;
            throw new IllegalStateException("HTTP " + response.statusCode() + " - " + snippet);
        }

        boolean isSse = contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
        StringBuilder rawBody = new StringBuilder();
        String finalB64 = null;
        String partialB64 = null;
        String finalUrl = null;
        String sseError = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!isSse && rawBody.isEmpty() && (trimmed.startsWith("event:") || trimmed.startsWith("data:"))) {
                    isSse = true;
                }
                if (isSse) {
                    if (!trimmed.startsWith("data:")) continue;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        JsonNode evt = objectMapper.readTree(data);
                        String type = evt.path("type").asText("");
                        String b64 = evt.path("b64_json").asText("");
                        if (b64.isBlank()) {
                            b64 = evt.path("data").path(0).path("b64_json").asText("");
                        }
                        String url = evt.path("url").asText("");
                        if (url.isBlank()) {
                            url = evt.path("data").path(0).path("url").asText("");
                        }
                        if (!b64.isBlank()) {
                            if (type.endsWith(".completed") || type.isEmpty()) {
                                finalB64 = b64;
                            } else {
                                partialB64 = b64;
                            }
                        }
                        if (!url.isBlank()) {
                            finalUrl = url;
                        }
                        if (evt.has("error")) {
                            sseError = evt.path("error").path("message").asText(evt.path("error").toString());
                        }
                    } catch (Exception ignored) {
                        // Skip non-JSON SSE keep-alive lines
                    }
                } else {
                    rawBody.append(line).append('\n');
                }
            }
        }

        if (isSse) {
            String chosenB64 = (finalB64 != null && !finalB64.isBlank()) ? finalB64 : partialB64;
            if (chosenB64 != null && !chosenB64.isBlank()) {
                return new GeneratedImageResult(Base64.getDecoder().decode(chosenB64), null, endpointUsed);
            }
            if (finalUrl != null && !finalUrl.isBlank()) {
                return resolveImageFromUrlOrDataUri(finalUrl, endpointUsed);
            }
            throw new IllegalStateException("SSE 流式生图未返回有效图片" + (sseError != null ? "：" + sseError : ""));
        }

        return parseImageApiResponse(rawBody.toString(), endpointUsed.replace(" (stream)", ""));
    }

    private String readStreamToString(InputStream is) {
        if (is == null) return "";
        try (InputStream in = is) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private GeneratedImageResult resolveImageFromUrlOrDataUri(String urlOrDataUri, String endpointUsed) {
        if (urlOrDataUri.startsWith("data:image/")) {
            int comma = urlOrDataUri.indexOf(',');
            if (comma > 0) {
                String b64 = urlOrDataUri.substring(comma + 1).replaceAll("\\s+", "");
                return new GeneratedImageResult(Base64.getDecoder().decode(b64), null, endpointUsed);
            }
        }
        try {
            HttpClient dl = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<byte[]> imgResp = dl.send(
                    HttpRequest.newBuilder(URI.create(urlOrDataUri)).timeout(Duration.ofSeconds(25)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (imgResp.statusCode() >= 200 && imgResp.statusCode() < 300 && imgResp.body() != null && imgResp.body().length > 1024) {
                return new GeneratedImageResult(imgResp.body(), urlOrDataUri, endpointUsed);
            }
        } catch (Exception ignored) {
            // Keep remote URL
        }
        return new GeneratedImageResult(null, urlOrDataUri, endpointUsed);
    }

    private GeneratedImageResult parseImageApiResponse(String responseBody, String endpointUsed) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode first = root.path("data").path(0);
        if (first.isMissingNode() || first.isNull()) {
            throw new IllegalStateException("上游生图响应缺少 data[0]");
        }
        String b64 = first.path("b64_json").asText("");
        if (!b64.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(b64);
            return new GeneratedImageResult(decoded, null, endpointUsed);
        }
        String url = first.path("url").asText("");
        if (!url.isBlank()) {
            return resolveImageFromUrlOrDataUri(url, endpointUsed);
        }
        throw new IllegalStateException("上游生图响应未包含 b64_json 或 url");
    }

    private void writeFormField(ByteArrayOutputStream baos, String boundary, String name, String value) throws Exception {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode callVisionForJson(AiConfigService.ResolvedAiCredentials creds, String systemPrompt,
                                       String userText, String imageDataUri) {
        return callVisionForJson(creds, systemPrompt, userText,
                (imageDataUri != null && !imageDataUri.isBlank()) ? List.of(imageDataUri) : Collections.emptyList());
    }

    private JsonNode callVisionForJson(AiConfigService.ResolvedAiCredentials creds, String systemPrompt,
                                       String userText, List<String> imageDataUris) {
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText));
        if (imageDataUris != null) {
            for (String uri : imageDataUris) {
                if (uri != null && !uri.isBlank()) {
                    userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", uri)));
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", creds.visionModel());
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        return executeWithJsonRetry(creds.baseUrl(), creds.apiKey(), body);
    }

    private JsonNode callTextForJson(AiConfigService.ResolvedAiCredentials creds, String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", creds.chatModel());
        body.put("temperature", 0.4);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return executeWithJsonRetry(creds.baseUrl(), creds.apiKey(), body);
    }

    private JsonNode executeWithJsonRetry(String baseUrl, String apiKey, Map<String, Object> body) {
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String content = sendChatCompletion(baseUrl, apiKey, body, readTimeoutMs);
                String cleaned = extractJsonObject(content);
                if (cleaned != null) {
                    return objectMapper.readTree(cleaned);
                }
                lastErr = new IllegalStateException("模型未返回合法 JSON 对象");
            } catch (Exception e) {
                lastErr = e;
                log.warn("LLM attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR,
                "多模态大模型调用失败或返回格式异常：" + (lastErr != null ? lastErr.getMessage() : "未知错误"));
    }

    private String sendChatCompletion(String baseUrl, String apiKey, Map<String, Object> body, long timeoutMs) throws Exception {
        String endpoint = trimSlash(baseUrl) + "/chat/completions";
        String payload = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String snippet = response.body() != null && response.body().length() > 220
                    ? response.body().substring(0, 220) : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode() + " - " + snippet);
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new IllegalStateException("上游接口响应缺少 choices[0].message.content");
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        // Handle structured content array format
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part.has("text")) sb.append(part.get("text").asText());
            }
            return sb.toString();
        }
        return contentNode.toString();
    }

    public String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String r = url.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }
}
