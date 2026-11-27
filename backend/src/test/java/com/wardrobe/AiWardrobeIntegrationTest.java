package com.wardrobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.service.AiConfigService;
import com.wardrobe.service.OutfitContactSheetService;
import com.wardrobe.service.OutfitRecommendationService;
import com.wardrobe.service.PreferenceService;
import com.wardrobe.service.SeedDataInitializer;
import com.wardrobe.service.StorageService;
import com.wardrobe.mapper.WardrobeItemMapper;
import com.wardrobe.model.entity.UserPreference;
import com.wardrobe.model.entity.WardrobeItem;
import com.wardrobe.service.client.DashScopeVtonClient;
import com.wardrobe.service.client.MultimodalLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiWardrobeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private SeedDataInitializer seedDataInitializer;

    @Autowired
    private OutfitRecommendationService outfitRecommendationService;

    @Autowired
    private OutfitContactSheetService outfitContactSheetService;

    @Autowired
    private PreferenceService preferenceService;

    @Autowired
    private WardrobeItemMapper wardrobeItemMapper;

    @MockBean
    private MultimodalLlmClient llmClient;

    @MockBean
    private DashScopeVtonClient dashScopeVtonClient;

    private String loginAsDemo() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "demo_user",
                                "password", "Wardrobe2026!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    void testAuthAndCrossUserIsolation() throws Exception {
        // 1. Unauthenticated request blocked with 40101
        mockMvc.perform(get("/api/wardrobe/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 2. Wrong password returns 40102
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "demo_user",
                                "password", "WrongPass999"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));

        // 3. Demo user has 11 seeded items
        String demoToken = loginAsDemo();
        MvcResult demoItemsRes = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode demoItems = objectMapper.readTree(demoItemsRes.getResponse().getContentAsString()).path("data");
        assertThat(demoItems.size()).isGreaterThanOrEqualTo(11);
        long demoItemId = demoItems.get(0).path("id").asLong();

        // 4. Register User B with empty wardrobe
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "isolated_user_b",
                                "nickname", "隔离测试号",
                                "password", "TestPass123",
                                "defaultCity", "上海",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String userBToken = objectMapper.readTree(regRes.getResponse().getContentAsString())
                .path("data").path("token").asText();

        // User B sees 0 items
        mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 5. User B attempting to modify or delete demo_user's item gets 40301
        mockMvc.perform(put("/api/wardrobe/items/" + demoItemId)
                        .header("Authorization", "Bearer " + userBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权篡改\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(delete("/api/wardrobe/items/" + demoItemId)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void testDynamicEncryptedApiKeyConfigRoundTrip() throws Exception {
        String token = loginAsDemo();
        String rawKey = "sk-live-secret-9876543210abcd";

        // Save API key from frontend endpoint
        MvcResult saveRes = mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "apiKey", rawKey,
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk-l****abcd"))
                .andReturn();
        assertThat(saveRes.getResponse().getContentAsString()).doesNotContain(rawKey);

        // Verify server resolves decrypted plaintext key immediately
        MvcResult meRes = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        long userId = objectMapper.readTree(meRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        AiConfigService.ResolvedAiCredentials resolved = aiConfigService.resolveForUser(userId);
        assertThat(resolved.active()).isTrue();
        assertThat(resolved.apiKey()).isEqualTo(rawKey);
        assertThat(resolved.visionModel()).isEqualTo("qwen-vl-max");

        // Update model with blank apiKey -> preserves stored encrypted key
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "openai",
                                "baseUrl", "https://api.openai.com/v1",
                                "apiKey", "",
                                "visionModel", "gpt-4o",
                                "chatModel", "gpt-4o-mini",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.visionModel").value("gpt-4o"));
        assertThat(aiConfigService.resolveForUser(userId).apiKey()).isEqualTo(rawKey);

        // Clear API key so other tests run in deterministic fallback mode
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "clearApiKey", true,
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(false));
    }

    @Test
    void testImageMagicByteValidationAndUpload() throws Exception {
        String token = loginAsDemo();

        // Fake .txt disguised as .jpg -> must fail with 40002
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "fake_shirt.jpg", "image/jpeg", "hello this is plain text not jpeg".getBytes());
        mockMvc.perform(multipart("/api/vision/recognize-item")
                        .file(disguised)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        // Valid minimal PNG header bytes -> accepted
        byte[] validPng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        MockMultipartFile realPng = new MockMultipartFile(
                "file", "beige_coat.png", "image/png", validPng);
        mockMvc.perform(multipart("/api/vision/recognize-item")
                        .file(realPng)
                        .param("sourceType", "photo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clothing").value(true))
                .andExpect(jsonPath("$.data.originalImageUrl").isNotEmpty());
    }

    @Test
    void testRecommendationSwapFavoriteAndCalendarConsistency() throws Exception {
        String token = loginAsDemo();

        // 1. Recommend for cool weather 18C
        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18,
                                "weatherCondition", "多云",
                                "budgetLimit", 300
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.slots").isArray())
                .andReturn();
        JsonNode planData = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data");
        long planId = planData.path("id").asLong();

        // 2. Toggle favorite
        mockMvc.perform(patch("/api/outfit/plans/" + planId + "/favorite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true));

        // 3. Swap bottom slot
        mockMvc.perform(post("/api/outfit/plans/" + planId + "/swap-slot")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("category", "bottom"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiReasoning").isNotEmpty());

        // 4. Record history and verify wear_count increment + reverse lookup
        MvcResult itemsRes = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstItem = objectMapper.readTree(itemsRes.getResponse().getContentAsString()).path("data").get(0);
        long itemId = firstItem.path("id").asLong();
        int prevWear = firstItem.path("wearCount").asInt();

        mockMvc.perform(post("/api/outfit/history")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "planId", planId,
                                "themeTitle", "测试今日采纳方案",
                                "wearDate", LocalDate.now().toString(),
                                "sceneTag", "职场通勤",
                                "weatherInfo", "多云 18℃",
                                "weatherTemp", 18,
                                "itemIds", List.of(itemId),
                                "feedbackScore", 5,
                                "notes", "集成测试记录"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themeTitle").value("测试今日采纳方案"));

        mockMvc.perform(get("/api/wardrobe/items/" + itemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.wearCount").value(prevWear + 1))
                .andExpect(jsonPath("$.data.relatedHistory").isNotEmpty());
    }

    @Test
    void testCpwAnalyticsAndChinaInspoFeedIsolation() throws Exception {
        String demoToken = loginAsDemo();

        // 1. Create priced item and verify CPW & preference total value
        MvcResult createdRes = mockMvc.perform(post("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "测试羊绒极简大衣",
                                "category", "outerwear",
                                "color", "燕麦",
                                "colorHex", "#DFD3C3",
                                "seasons", List.of("秋", "冬"),
                                "price", 1200.00
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(1200.00))
                .andExpect(jsonPath("$.data.costPerWear").value(1200.00))
                .andReturn();
        long createdId = objectMapper.readTree(createdRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/preferences")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalWardrobeValue").isNumber())
                .andExpect(jsonPath("$.data.avgCostPerWear").isNumber())
                .andExpect(jsonPath("$.data.pricedItemCount").isNumber());

        // Cleanup created test item
        mockMvc.perform(delete("/api/wardrobe/items/" + createdId)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk());

        // 2. China Inspo feed & like toggle
        mockMvc.perform(get("/api/inspo/feed")
                        .param("tab", "top")
                        .param("gender", "womens")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.looks").isArray())
                .andExpect(jsonPath("$.data.looks[0].rank").value(1))
                .andExpect(jsonPath("$.data.looks[0].pieces[0].taobaoUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.looks[0].pieces[0].jdUrl").isNotEmpty());

        mockMvc.perform(post("/api/inspo/looks/look-w1/like")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));

        // Toggle off to keep state clean
        mockMvc.perform(post("/api/inspo/looks/look-w1/like")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false));
    }

    @Test
    void testCollageLayoutPersistenceAndTryOnPreviewAndModelReference() throws Exception {
        String demoToken = loginAsDemo();

        // 1. Create a plan
        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 2. Save custom collage layout and verify persistence
        mockMvc.perform(put("/api/outfit/plans/" + planId + "/collage-layout")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nodes", List.of(Map.of(
                                        "nodeKey", "top-1",
                                        "slotKey", "top",
                                        "itemId", 1,
                                        "x", 42.5,
                                        "y", 28.0,
                                        "scale", 1.15,
                                        "rotation", -4.0,
                                        "zIndex", 3
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collageLayout[0].x").value(42.5))
                .andExpect(jsonPath("$.data.collageLayout[0].zIndex").value(3));

        // 3. Upload valid PNG model reference photo + reject disguised fake file
        MockMultipartFile badRef = new MockMultipartFile("file", "bad.png", "image/png", "not-an-image".getBytes());
        mockMvc.perform(multipart("/api/ai-config/model-reference")
                        .file(badRef)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        byte[] validPng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        MockMultipartFile goodRef = new MockMultipartFile("file", "me.png", "image/png", validPng);
        mockMvc.perform(multipart("/api/ai-config/model-reference")
                        .file(goodRef)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelReferenceUrl").isNotEmpty());

        // 4. Generate AI Try-On Preview (graceful fallback without real external key)
        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stylePreset", "studio_white",
                                "useReferencePhoto", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.promptUsed").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport").isMap())
                .andExpect(jsonPath("$.data.visualReport.bodyProfile").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport.sceneLabel").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport.overallPalette").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport.pieces").isArray())
                .andExpect(jsonPath("$.data.visualReport.pieces[0].zhSummary").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport.pieces[0].enPrompt").isNotEmpty());

        // Security check: StorageService.readGarmentImageForAi blocks path traversal & cross-user uploads
        assertThat(storageService.readGarmentImageForAi(1L, "/alta/pieces/../../etc/passwd")).isNull();
        assertThat(storageService.readGarmentImageForAi(1L, "/uploads/user_999/secret.png")).isNull();

        // Cleanup reference photo
        mockMvc.perform(delete("/api/ai-config/model-reference")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelReferenceUrl").doesNotExist());
    }

    @Test
    void testAsyncTryOnJobPersistenceAndIdorIsolation() throws Exception {
        String demoToken = loginAsDemo();

        // 1. Create a plan and verify GET /api/outfit/plans/{id}
        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 20
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/outfit/plans/" + planId)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(planId));

        // 2. Submit async try-on job and poll until SUCCEEDED
        MvcResult submitRes = mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on/jobs")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stylePreset", "studio_white",
                                "generationMode", "auto",
                                "useReferencePhoto", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long jobId = objectMapper.readTree(submitRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        String status = "PROCESSING";
        String previewUrl = "";
        for (int i = 0; i < 30; i++) {
            MvcResult pollRes = mockMvc.perform(get("/api/outfit/try-on/jobs/" + jobId)
                            .header("Authorization", "Bearer " + demoToken))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = objectMapper.readTree(pollRes.getResponse().getContentAsString()).path("data");
            status = data.path("status").asText();
            previewUrl = data.path("previewImageUrl").asText("");
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(previewUrl).isNotBlank();

        // 3. List recent jobs and verify persistence (including visualReport) across reloads
        mockMvc.perform(get("/api/outfit/try-on/jobs")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(jobId))
                .andExpect(jsonPath("$.data[0].previewImageUrl").value(previewUrl))
                .andExpect(jsonPath("$.data[0].visualReport").isMap())
                .andExpect(jsonPath("$.data[0].visualReport.pieces").isArray());

        // 4. Register another user and verify cross-user IDOR on job & plan returns 40301
        String otherUser = "idor_tryon_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", otherUser,
                                "password", "TestPass123!",
                                "nickname", "OtherUser"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String otherToken = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data").path("token").asText();

        mockMvc.perform(get("/api/outfit/try-on/jobs/" + jobId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(delete("/api/outfit/try-on/jobs/" + jobId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 5. Apply cover & delete by owner succeeds
        mockMvc.perform(post("/api/outfit/try-on/jobs/" + jobId + "/apply-cover")
                        .param("planId", String.valueOf(planId))
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tryOnImageUrl").value(previewUrl));

        mockMvc.perform(delete("/api/outfit/try-on/jobs/" + jobId)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk());
    }

    @Test
    void testPersonalizationMirrorBodyShapeColorSeasonDislikeAndInspoRanking() throws Exception {
        String demoToken = loginAsDemo();

        // 1. Update bodyShape, colorSeason, heightCm & verify Style Mirror analytics
        mockMvc.perform(put("/api/preferences")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bodyShape", "A型梨形",
                                "colorSeason", "秋季暖深",
                                "heightCm", 163
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bodyShape").value("A型梨形"))
                .andExpect(jsonPath("$.data.colorSeason").value("秋季暖深"))
                .andExpect(jsonPath("$.data.heightCm").value(163))
                .andExpect(jsonPath("$.data.bodyShapeAdvice").isNotEmpty())
                .andExpect(jsonPath("$.data.colorSeasonAdvice").isNotEmpty())
                .andExpect(jsonPath("$.data.coreStaples").isArray())
                .andExpect(jsonPath("$.data.neglectedPieces").isArray())
                .andExpect(jsonPath("$.data.colorPairFingerprints").isArray());

        // 2. Revive Idle Mode recommendation & Dislike negative feedback replacement
        MvcResult reviveRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18,
                                "reviveIdle", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long planId = objectMapper.readTree(reviveRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(post("/api/outfit/plans/" + planId + "/dislike")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber());

        // 3. Personalized Inspo "For You" matchScore & matchReason
        mockMvc.perform(get("/api/inspo/feed")
                        .param("tab", "for_you")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.looks[0].matchScore").isNumber())
                .andExpect(jsonPath("$.data.looks[0].matchReason").isNotEmpty());
    }

    private static final byte[] VALID_PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Test
    void testMultiItemDetectionReturnsBoundingBoxesAndStoresCrops() throws Exception {
        String username = "multi_detect_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "TestPass123!",
                                "nickname", "MultiDetect",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data").path("token").asText();
        MockMultipartFile png = new MockMultipartFile("file", "outfit.png", "image/png", VALID_PNG_BYTES);
        MockMultipartFile fake = new MockMultipartFile("file", "fake.png", "image/png", "not-an-image".getBytes());

        // 1. JWT is mandatory
        mockMvc.perform(multipart("/api/vision/detect-items").file(png))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 2. No model configured: empty list, nothing stored on disk, no error
        mockMvc.perform(multipart("/api/vision/detect-items").file(png)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedRealLlm").value(false))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.originalImageUrl").doesNotExist());

        // 3. Magic-byte validation happens before anything else
        mockMvc.perform(multipart("/api/vision/detect-items").file(fake)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        // 4. Seed a wardrobe piece that the detector should flag as a duplicate
        mockMvc.perform(post("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "直筒牛仔裤",
                                "category", "bottom",
                                "color", "牛仔蓝",
                                "seasons", List.of("春", "秋")
                        ))))
                .andExpect(status().isOk());

        // 5. Enable a (fake) key and stub the model response: fractions, percentages and an unusable box
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "test",
                                "baseUrl", "http://127.0.0.1:9/v1",
                                "apiKey", "sk-test-multi-detect-1234567890",
                                "visionModel", "gpt-4o-mini",
                                "chatModel", "gpt-4o-mini",
                                "enabled", true
                        ))))
                .andExpect(status().isOk());

        when(llmClient.detectClothingItems(any(), anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"isClothing": true, "items": [
                  {"name":"条纹针织衫","category":"top","color":"藏蓝","season":"spring,autumn","styleTags":["法式松弛"],"bbox":[0.2,0.1,0.6,0.35]},
                  {"name":"直筒牛仔裤","category":"bottom","color":"牛仔蓝","season":"spring,autumn","bbox":[30,50,40,45]},
                  {"name":"德训鞋","category":"shoes","color":"米白","bbox":"unknown"}
                ]}
                """));

        MvcResult detectRes = mockMvc.perform(multipart("/api/vision/detect-items").file(png)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedRealLlm").value(true))
                .andExpect(jsonPath("$.data.originalImageUrl").value(org.hamcrest.Matchers.startsWith("/uploads/")))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].bbox.x").value(0.2))
                .andExpect(jsonPath("$.data.items[0].bbox.h").value(0.35))
                .andExpect(jsonPath("$.data.items[1].bbox.x").value(0.3))
                .andExpect(jsonPath("$.data.items[1].bbox.w").value(0.4))
                .andExpect(jsonPath("$.data.items[2].bbox").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].similarWardrobeName").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].similarWardrobeName").value("直筒牛仔裤"))
                .andReturn();
        JsonNode detectData = objectMapper.readTree(detectRes.getResponse().getContentAsString()).path("data");
        assertThat(detectData.path("items").get(0).path("category").asText()).isEqualTo("top");
        assertThat(detectData.path("items").get(0).path("seasons").isArray()).isTrue();

        // 6. Non-clothing photo -> 42201
        when(llmClient.detectClothingItems(any(), anyString(), anyString())).thenReturn(
                objectMapper.readTree("{\"isClothing\": false, \"items\": []}"));
        mockMvc.perform(multipart("/api/vision/detect-items").file(png)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(42201));

        // 7. store-item-image: crop + cutout land in the caller's own upload folder
        MockMultipartFile cutout = new MockMultipartFile("cutoutFile", "cutout.png", "image/png", VALID_PNG_BYTES);
        mockMvc.perform(multipart("/api/vision/store-item-image").file(png).file(cutout)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cutoutFallback").value(false))
                .andExpect(jsonPath("$.data.originalImageUrl").value(org.hamcrest.Matchers.containsString("/user_")))
                .andExpect(jsonPath("$.data.processedImageUrl").value(org.hamcrest.Matchers.containsString("cutout")));

        // a broken cutout degrades to the crop; a broken crop is rejected with 40002
        MockMultipartFile badCutout = new MockMultipartFile("cutoutFile", "cutout.png", "image/png", "nope".getBytes());
        mockMvc.perform(multipart("/api/vision/store-item-image").file(png).file(badCutout)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cutoutFallback").value(true));
        mockMvc.perform(multipart("/api/vision/store-item-image").file(fake)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(multipart("/api/vision/store-item-image").file(png))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testVisualContactSheetCritiqueAndOotdPhotoCalendarAndIdorIsolation() throws Exception {
        String demoToken = loginAsDemo();
        MockMultipartFile png = new MockMultipartFile("file", "ootd.png", "image/png", VALID_PNG_BYTES);
        MockMultipartFile fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "plain-text-not-img".getBytes());

        // 1. Outfit critique rejects non-image magic bytes (40002)
        mockMvc.perform(multipart("/api/outfit/critique")
                        .file(fake)
                        .param("sceneTag", "职场通勤")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        // 2. Fetch demo items & run local fallback critique (no API key active)
        MvcResult itemsRes = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode itemsArr = objectMapper.readTree(itemsRes.getResponse().getContentAsString()).path("data");
        long item1 = itemsArr.get(0).path("id").asLong();
        long item2 = itemsArr.get(1).path("id").asLong();

        mockMvc.perform(multipart("/api/outfit/critique")
                        .file(png)
                        .param("sceneTag", "职场通勤")
                        .param("itemIds", item1 + "," + item2)
                        .param("source", "studio_canvas")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").isNumber())
                .andExpect(jsonPath("$.data.colorScore").isNumber())
                .andExpect(jsonPath("$.data.proportionScore").isNumber())
                .andExpect(jsonPath("$.data.sceneScore").isNumber())
                .andExpect(jsonPath("$.data.headline").isNotEmpty())
                .andExpect(jsonPath("$.data.highlights").isArray())
                .andExpect(jsonPath("$.data.improvements").isArray())
                .andExpect(jsonPath("$.data.usedRealVlm").value(false));

        // 3. OOTD real-photo check-in on a dedicated date -> persists ootdPhotoUrl & increments wearCount
        String checkDate = LocalDate.now().minusDays(2).toString();
        MvcResult checkinRes = mockMvc.perform(multipart("/api/outfit/history/ootd-checkin")
                        .file(png)
                        .param("wearDate", checkDate)
                        .param("themeTitle", "实拍对镜 OOTD")
                        .param("sceneTag", "周末咖啡")
                        .param("itemIds", "[" + item1 + "," + item2 + "]")
                        .param("notes", "阳光很好")
                        .param("feedbackScore", "5")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ootdPhotoUrl").value(org.hamcrest.Matchers.startsWith("/uploads/user_")))
                .andExpect(jsonPath("$.data.themeTitle").value("实拍对镜 OOTD"))
                .andReturn();
        long historyId = objectMapper.readTree(checkinRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 4. Another user cannot overwrite or delete demo's OOTD photo (40301 IDOR protection)
        String otherUser = "idor_ootd_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", otherUser,
                                "password", "TestPass123!",
                                "nickname", "OtherOotd",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk()).andReturn();
        String otherToken = objectMapper.readTree(regRes.getResponse().getContentAsString())
                .path("data").path("token").asText();

        mockMvc.perform(multipart("/api/outfit/history/" + historyId + "/photo")
                        .file(png)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(delete("/api/outfit/history/" + historyId + "/photo")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 5. Owner replaces and deletes OOTD photo cleanly
        mockMvc.perform(multipart("/api/outfit/history/" + historyId + "/photo")
                        .file(png)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ootdPhotoUrl").value(org.hamcrest.Matchers.startsWith("/uploads/user_")));

        mockMvc.perform(delete("/api/outfit/history/" + historyId + "/photo")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ootdPhotoUrl").doesNotExist());

        // 6. Visual Contact Sheet VLM styling + VLM critique with active key (mocked VLM)
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "test",
                                "baseUrl", "http://127.0.0.1:9/v1",
                                "apiKey", "sk-test-vlm-contact-sheet-999999",
                                "visionModel", "gpt-4o",
                                "chatModel", "gpt-4o-mini",
                                "enabled", true
                        ))))
                .andExpect(status().isOk());

        when(llmClient.curateOutfitPlanWithContactSheet(any(), anyString(), anyString()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "themeTitle": "视觉审图 · 极简燕麦通勤",
                          "selectedItemIds": [%d, %d],
                          "missingItems": [],
                          "aiReasoning": "通过视觉联络表确认色温统一、面料质感呼应。",
                          "matchScore": 96.5
                        }
                        """.formatted(item1, item2)));

        mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 19
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedRealLlm").value(true))
                .andExpect(jsonPath("$.data.usedVisualReview").value(true))
                .andExpect(jsonPath("$.data.themeTitle").value("视觉审图 · 极简燕麦通勤"));

        when(llmClient.critiqueOutfitVisual(any(), anyString(), anyString()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "overallScore": 90,
                          "colorScore": 93,
                          "proportionScore": 87,
                          "sceneScore": 91,
                          "headline": "色彩低饱和克制，比例干净利落",
                          "highlights": ["燕麦与炭黑形成柔和明暗对比"],
                          "improvements": ["内搭下摆可微塞凸显高腰线"],
                          "suggestedSwap": {
                            "itemId": %d,
                            "itemName": "替换建议",
                            "category": "accessory",
                            "reason": "提升整体完整度"
                          }
                        }
                        """.formatted(itemsArr.get(2).path("id").asLong())));

        mockMvc.perform(multipart("/api/outfit/critique")
                        .file(png)
                        .param("sceneTag", "职场通勤")
                        .param("itemIds", item1 + "," + item2)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedRealVlm").value(true))
                .andExpect(jsonPath("$.data.overallScore").value(90))
                .andExpect(jsonPath("$.data.suggestedSwap.itemId").value(itemsArr.get(2).path("id").asLong()));

        // Clear mock key so test state stays clean
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "clearApiKey", true,
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true
                        ))))
                .andExpect(status().isOk());
    }

    @Test
    void testBailianVtonConfigAndVirtualTryOnAndFallback() throws Exception {
        String username = "vton_user_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "TestPass123!",
                                "nickname", "VtonTester",
                                "loadStarterPack", true
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode regData = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data");
        String token = regData.path("token").asText();
        long userId = regData.path("userInfo").path("id").asLong();

        // 1. Reuse DashScope main key & auto-migrate legacy aitryon-plus -> qwen-image-2.1-pro
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "apiKey", "sk-dashscope-main-1234567890",
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true,
                                "vtonEnabled", true,
                                "vtonProvider", "dashscope",
                                "vtonModel", "aitryon-plus",
                                "vtonPublicBaseUrl", "http://47.111.163.151:8680"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vtonEnabled").value(true))
                .andExpect(jsonPath("$.data.vtonModel").value("qwen-image-2.1-pro"))
                .andExpect(jsonPath("$.data.hasVtonApiKey").value(false))
                .andExpect(jsonPath("$.data.vtonKeyReusesMain").value(true))
                .andExpect(jsonPath("$.data.vtonPublicBaseUrl").value("http://47.111.163.151:8680"));

        // 2. Save dedicated encrypted VTON key with qwen-image-2.1-turbo and verify masking (plaintext never leaks)
        String secretVtonKey = "sk-vton-dedicated-9988776655";
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true,
                                "vtonEnabled", true,
                                "vtonProvider", "dashscope",
                                "vtonModel", "qwen-image-2.1-turbo",
                                "vtonApiKey", secretVtonKey,
                                "vtonPublicBaseUrl", "http://47.111.163.151:8680/"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVtonApiKey").value(true))
                .andExpect(jsonPath("$.data.vtonApiKeyMasked").isNotEmpty())
                .andExpect(jsonPath("$.data.vtonKeyReusesMain").value(false))
                .andExpect(jsonPath("$.data.vtonModel").value("qwen-image-2.1-turbo"))
                .andExpect(jsonPath("$.data.vtonPublicBaseUrl").value("http://47.111.163.151:8680"));

        // 3. Create an outfit plan and run Bailian multi-image VTON (starter .webp garments -> JPEG Base64 data URIs)
        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        when(dashScopeVtonClient.runMultiImageTryOn(anyString(), anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    String model = inv.getArgument(1);
                    String personDataUri = inv.getArgument(2);
                    @SuppressWarnings("unchecked")
                    java.util.List<DashScopeVtonClient.GarmentImageInput> garments =
                            (java.util.List<DashScopeVtonClient.GarmentImageInput>) inv.getArgument(3);
                    String prompt = inv.getArgument(4);

                    assertThat(key).isEqualTo(secretVtonKey);
                    assertThat(model).isEqualTo("qwen-image-2.1-turbo");
                    assertThat(personDataUri).startsWith("data:image/jpeg;base64,");
                    assertThat(garments).isNotEmpty();
                    assertThat(garments.size()).isLessThanOrEqualTo(5);
                    for (DashScopeVtonClient.GarmentImageInput g : garments) {
                        assertThat(g.imageDataOrUrl()).startsWith("data:image/jpeg;base64,");
                    }
                    assertThat(prompt).contains("[图1]");
                    assertThat(prompt).contains("[图2]");

                    byte[] fakeOut = new byte[160];
                    System.arraycopy(VALID_PNG_BYTES, 0, fakeOut, 0, VALID_PNG_BYTES.length);
                    return new DashScopeVtonClient.VtonResult(fakeOut, "https://oss.example/temp.png", "task-123", model);
                });

        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "modelPreset", "/alta/models/female-1.jpg",
                                "upperGarmentPreference", "outerwear",
                                "stylePreset", "street_shanghai",
                                "customPrompt", "柔和下午日光"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedRealImageModel").value(true))
                .andExpect(jsonPath("$.data.endpointUsed").value("dashscope/qwen-image-2.1-turbo"))
                .andExpect(jsonPath("$.data.previewImageUrl").value(org.hamcrest.Matchers.startsWith("/uploads/user_" + userId + "/tryon_vton_")))
                .andExpect(jsonPath("$.data.visualReport.engineType").value("dashscope_vton"))
                .andExpect(jsonPath("$.data.visualReport.pieces[?(@.vtonApplied == true)]").isNotEmpty());

        // 4. When DashScope throws an exception, seamlessly fall back (HTTP 200) with vtonFallbackReason
        org.mockito.Mockito.doThrow(new IllegalStateException("Mock DashScope rate limit"))
                .when(dashScopeVtonClient)
                .runMultiImageTryOn(anyString(), anyString(), anyString(), anyList(), anyString());
        when(llmClient.generateEditorialTryOn(any(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("Mock general fallback"));

        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "modelPreset", "/alta/models/female-1.jpg"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.visualReport.engineType").value("editorial_fallback"))
                .andExpect(jsonPath("$.data.visualReport.vtonFallbackReason").isNotEmpty());

        // 5. Loopback 127.0.0.1 STILL succeeds via Base64 direct pass, and qwen-image-3.0-pro caps at 2 garments!
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true,
                                "vtonEnabled", true,
                                "vtonModel", "qwen-image-3.0-pro",
                                "vtonPublicBaseUrl", "http://127.0.0.1:5173"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vtonModel").value("qwen-image-3.0-pro"));

        org.mockito.Mockito.reset(dashScopeVtonClient);
        when(dashScopeVtonClient.runMultiImageTryOn(anyString(), anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    String model = inv.getArgument(1);
                    @SuppressWarnings("unchecked")
                    java.util.List<DashScopeVtonClient.GarmentImageInput> garments =
                            (java.util.List<DashScopeVtonClient.GarmentImageInput>) inv.getArgument(3);
                    assertThat(model).isEqualTo("qwen-image-3.0-pro");
                    assertThat(garments.size()).isLessThanOrEqualTo(2);
                    byte[] fakeOut = new byte[160];
                    System.arraycopy(VALID_PNG_BYTES, 0, fakeOut, 0, VALID_PNG_BYTES.length);
                    return new DashScopeVtonClient.VtonResult(fakeOut, "https://oss.example/temp3.png", "task-300", model);
                });

        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "modelPreset", "/alta/models/male-1.jpg",
                                "clientOrigin", "http://127.0.0.1:5173"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endpointUsed").value("dashscope/qwen-image-3.0-pro"))
                .andExpect(jsonPath("$.data.visualReport.engineType").value("dashscope_vton"));

        // 6. Security check: svg:// returns null cleanly, while cross-user uploads & traversal throw
        assertThat(storageService.prepareVtonBase64DataUri(userId, "svg://tshirt", null)).isNull();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                storageService.prepareVtonBase64DataUri(userId, "/uploads/user_999999/secret.jpg", null));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                storageService.prepareVtonBase64DataUri(userId, "/alta/pieces/../../etc/passwd", null));
    }

    @Test
    void testCuratedDemoItemsSeededIdempotently() throws Exception {
        String demoToken = loginAsDemo();

        MvcResult res = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(
                res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertThat(items.size()).isGreaterThanOrEqualTo(74);

        Map<String, String> processedByName = new java.util.HashMap<>();
        Map<String, String> categoryByName = new java.util.HashMap<>();
        for (JsonNode item : items) {
            processedByName.put(item.path("name").asText(), item.path("processedImageUrl").asText());
            categoryByName.put(item.path("name").asText(), item.path("category").asText());
        }
        assertThat(processedByName.get("炭灰毛领修身短款辣妹夹克")).startsWith("/alta/pieces/demo-p");
        assertThat(processedByName.get("极简黑漆皮尖头细跟露跟高跟鞋")).startsWith("/alta/pieces/demo-p");
        assertThat(processedByName.get("灰调细条纹辣妹显瘦微喇长裤")).startsWith("/alta/pieces/demo-p");
        assertThat(processedByName.get("柔粉漆皮双蝴蝶结厚底玛丽珍鞋")).isEqualTo("/alta/pieces/demo-p67-cutout.png");
        assertThat(processedByName.get("暗黑哥特黑纱蕾丝荷叶边立领宫廷衬衫")).isEqualTo("/alta/pieces/demo-p70-cutout.png");
        assertThat(processedByName.get("法式米白尖头珍珠绊带猫跟单鞋")).isEqualTo("/alta/pieces/demo-p72-cutout.png");
        assertThat(processedByName.get("复古深咖双排扣英伦羊毛大衣")).isEqualTo("/alta/pieces/brown-wool-coat.webp");
        assertThat(processedByName.get("极简全黑长款修身西装大衣")).isEqualTo("/alta/pieces/black-blazer.webp");
        assertThat(processedByName.get("暗夜蕾丝花边超薄透肤黑丝袜")).isEqualTo("/alta/pieces/demo-p73-cutout.png");
        assertThat(processedByName.get("法式蕾丝蝴蝶结吊带过膝黑丝袜")).isEqualTo("/alta/pieces/demo-p74-cutout.png");
        assertThat(processedByName.get("日系收腰短袖海军领格纹结JK水手服上衣")).isEqualTo("/alta/pieces/demo-p76-cutout.png");
        assertThat(processedByName.get("日系软糯纯白粗针织宽松堆堆袜")).isEqualTo("/alta/pieces/demo-p79-cutout.png");
        assertThat(processedByName.get("纯欲辣妹黑色挂脖深V前扣露背短上衣")).isEqualTo("/alta/pieces/demo-p81-cutout.png");
        assertThat(processedByName.get("极简全黑高腰辣妹包臀A字超短裙")).isEqualTo("/alta/pieces/black-mini-skirt.webp");

        // Verify Plan A 7-category taxonomy classification on demo_user
        assertThat(categoryByName.get("黑金绳结扣小香风链条单肩包")).isEqualTo("bag");
        assertThat(categoryByName.get("燕麦帆布拼皮托特包")).isEqualTo("bag");
        assertThat(categoryByName.get("炭黑 包袋")).isEqualTo("bag");
        assertThat(categoryByName.get("暗夜蕾丝花边超薄透肤黑丝袜")).isEqualTo("accessory");
        assertThat(categoryByName.get("日系软糯纯白粗针织宽松堆堆袜")).isEqualTo("accessory");
        assertThat(categoryByName.get("金属三角项链")).isEqualTo("accessory");
        assertThat(categoryByName.get("暗黑哥特银黑提花束腰洛丽塔JSK")).isEqualTo("dress");
        assertThat(categoryByName.get("草莓庄园蕾丝蝴蝶结甜美洛丽塔裙")).isEqualTo("dress");
        assertThat(categoryByName.get("酒红深V铆钉挂脖褶皱露背超短连衣裙")).isEqualTo("dress");
        assertThat(categoryByName.get("复古焦糖格纹高腰JK百褶超短裙")).isEqualTo("bottom");

        // Re-running backfill + migrateLegacyCategories must remain idempotent and upgrade legacy rows
        com.wardrobe.model.entity.WardrobeItem legacyBag = new com.wardrobe.model.entity.WardrobeItem();
        legacyBag.setUserId(items.get(0).path("userId").asLong());
        legacyBag.setName("旧版分类腋下斜挎包");
        legacyBag.setCategory("accessory");
        legacyBag.setColor("炭黑");
        legacyBag.setColorHex("#1C1C1E");
        legacyBag.setSeason("spring,autumn");
        legacyBag.setThickness(1);
        legacyBag.setSvgType("crossbody_bag");
        legacyBag.setProcessedImageUrl("svg://crossbody_bag");
        legacyBag.setSourceType("manual");
        legacyBag.setWearCount(0);
        legacyBag.setCreatedAt(java.time.LocalDateTime.now());
        legacyBag.setUpdatedAt(java.time.LocalDateTime.now());
        wardrobeItemMapper.insert(legacyBag);

        com.wardrobe.model.entity.WardrobeItem legacyDress = new com.wardrobe.model.entity.WardrobeItem();
        legacyDress.setUserId(items.get(0).path("userId").asLong());
        legacyDress.setName("旧版分类法式碎花连衣裙");
        legacyDress.setCategory("top");
        legacyDress.setColor("米白");
        legacyDress.setColorHex("#F3EDE2");
        legacyDress.setSeason("spring,summer");
        legacyDress.setThickness(1);
        legacyDress.setSvgType("pleated_skirt");
        legacyDress.setProcessedImageUrl("svg://skirt");
        legacyDress.setSourceType("manual");
        legacyDress.setWearCount(0);
        legacyDress.setCreatedAt(java.time.LocalDateTime.now());
        legacyDress.setUpdatedAt(java.time.LocalDateTime.now());
        wardrobeItemMapper.insert(legacyDress);

        seedDataInitializer.backfillDemoCuratedItems();
        seedDataInitializer.migrateLegacyCategories();

        assertThat(wardrobeItemMapper.selectById(legacyBag.getId()).getCategory()).isEqualTo("bag");
        assertThat(wardrobeItemMapper.selectById(legacyDress.getId()).getCategory()).isEqualTo("dress");
        wardrobeItemMapper.deleteById(legacyBag.getId());
        wardrobeItemMapper.deleteById(legacyDress.getId());

        int countBefore = items.size();
        seedDataInitializer.backfillDemoCuratedItems();
        MvcResult resAfter = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode itemsAfter = objectMapper.readTree(
                resAfter.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertThat(itemsAfter.size()).isEqualTo(countBefore);
    }

    @Test
    void testMultiplicativeScoringFeedbackLearningAnd12TileContactSheet() throws Exception {
        String username = "score_learn_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "TestPass123!",
                                "nickname", "ScoreLearner",
                                "loadStarterPack", true
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode regData = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data");
        String token = regData.path("token").asText();
        long userId = regData.path("userInfo").path("id").asLong();

        // Add extra items so user has >= 15 items (> 12) + verify disliked color hard veto
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(post("/api/wardrobe/items")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "极简基础单品" + i,
                                    "category", i % 2 == 0 ? "bottom" : "top",
                                    "color", "燕麦",
                                    "colorHex", "#DFD3C3",
                                    "season", "spring,autumn",
                                    "thickness", 2,
                                    "styleTags", List.of("极简通勤"),
                                    "processedImageUrl", "svg://shirt"
                            ))))
                    .andExpect(status().isOk());
        }
        for (Map.Entry<String, String> extra : List.of(
                Map.entry("荧光绿通勤衬衫", "荧光绿"),
                Map.entry("米白针织小背心", "米白")
        )) {
            mockMvc.perform(post("/api/wardrobe/items")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", extra.getKey(),
                                    "category", "top",
                                    "color", extra.getValue(),
                                    "colorHex", "荧光绿".equals(extra.getValue()) ? "#39FF14" : "#FAF6EE",
                                    "season", "spring,autumn",
                                    "thickness", 2,
                                    "styleTags", List.of("极简通勤", "Clean Fit"),
                                    "processedImageUrl", "svg://shirt"
                            ))))
                    .andExpect(status().isOk());
        }

        List<WardrobeItem> wardrobe = wardrobeItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WardrobeItem>()
                        .eq(WardrobeItem::getUserId, userId));
        assertThat(wardrobe.size()).isGreaterThanOrEqualTo(13);

        // 12-tile contact sheet limit verification
        OutfitContactSheetService.ContactSheet sheet = outfitContactSheetService.buildContactSheet(userId, wardrobe);
        assertThat(sheet).isNotNull();
        assertThat(sheet.totalTiles()).isLessThanOrEqualTo(12);
        assertThat(sheet.dataUri()).startsWith("data:image/jpeg;base64,");

        // Multiplicative colorGate must suppress explicit disliked color ("荧光绿") far below matching top
        UserPreference pref = preferenceService.getOrCreateEntity(userId);
        List<OutfitRecommendationService.ScoredItem> initialScored = outfitRecommendationService.scoreWardrobeItems(
                wardrobe, pref, 18, "职场通勤", Map.of(), false, java.util.Set.of());
        double neonScore = initialScored.stream()
                .filter(s -> "荧光绿通勤衬衫".equals(s.item().getName()))
                .findFirst().orElseThrow().score();
        double creamScore = initialScored.stream()
                .filter(s -> "米白针织小背心".equals(s.item().getName()))
                .findFirst().orElseThrow().score();
        assertThat(neonScore).isLessThan(creamScore * 0.45);

        // Trigger recommendation + favorite -> verifies learned_color_scores and learned_pair_scores persist
        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(patch("/api/outfit/plans/" + planId + "/favorite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true));

        UserPreference updatedPref = preferenceService.getOrCreateEntity(userId);
        assertThat(updatedPref.getLearnedColorScores()).isNotBlank();
        assertThat(updatedPref.getLearnedPairScores()).isNotBlank();
        assertThat(updatedPref.getLearnedPairScores()).contains("_");
    }

    @Test
    void testTryOnVlmQualityGateAutoRetryAndOptOut() throws Exception {
        String username = "vton_qc_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "TestPass123!",
                                "nickname", "QcTester",
                                "loadStarterPack", true
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String token = regRes.getResponse().getContentAsString();
        token = objectMapper.readTree(token).path("data").path("token").asText();

        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "dashscope",
                                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "apiKey", "sk-dashscope-qc-1234567890",
                                "visionModel", "qwen-vl-max",
                                "chatModel", "qwen-plus",
                                "enabled", true,
                                "vtonEnabled", true,
                                "vtonModel", "qwen-image-2.1-pro"
                        ))))
                .andExpect(status().isOk());

        MvcResult recRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "daily_weather",
                                "weatherTemp", 18
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(recRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        java.util.List<String> capturedPrompts = new java.util.ArrayList<>();
        org.mockito.Mockito.reset(dashScopeVtonClient, llmClient);
        when(llmClient.toCompactVisionDataUri(anyString(), anyString(), any()))
                .thenReturn("data:image/jpeg;base64,/9j/4AAQSkZJRg==");
        when(dashScopeVtonClient.runMultiImageTryOn(anyString(), anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    capturedPrompts.add(inv.getArgument(4));
                    byte[] fakeOut = new byte[160];
                    System.arraycopy(VALID_PNG_BYTES, 0, fakeOut, 0, VALID_PNG_BYTES.length);
                    return new DashScopeVtonClient.VtonResult(fakeOut, "https://oss.example/qc.png", "qc-task", "qwen-image-2.1-pro");
                });

        JsonNode qcFailNode = objectMapper.readTree("""
                {"passed":false,"score":55,"summary":"下装色彩偏差","issues":["裤子颜色变浅"],"correctionHint":"保持炭黑微喇裤"}
                """);
        JsonNode qcPassNode = objectMapper.readTree("""
                {"passed":true,"score":92,"summary":"人物一致且单品高度还原","issues":[],"correctionHint":""}
                """);
        when(llmClient.verifyTryOnQuality(any(), anyString(), anyList()))
                .thenReturn(qcFailNode)
                .thenReturn(qcPassNode);

        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "modelPreset", "/alta/models/female-1.jpg",
                                "enableQualityCheck", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visualReport.qualityCheckExecuted").value(true))
                .andExpect(jsonPath("$.data.visualReport.qualityCheckPassed").value(true))
                .andExpect(jsonPath("$.data.visualReport.qualityScore").value(92))
                .andExpect(jsonPath("$.data.visualReport.qualityRetryCount").value(1));

        assertThat(capturedPrompts).hasSize(2);
        assertThat(capturedPrompts.get(1)).contains("保持炭黑微喇裤");

        // Opt-out case: enableQualityCheck=false must skip VLM quality verification and call VTON only once
        org.mockito.Mockito.clearInvocations(dashScopeVtonClient, llmClient);
        mockMvc.perform(post("/api/outfit/plans/" + planId + "/try-on")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "modelPreset", "/alta/models/female-1.jpg",
                                "enableQualityCheck", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visualReport.qualityCheckExecuted").value(false));

        org.mockito.Mockito.verify(dashScopeVtonClient, org.mockito.Mockito.times(1))
                .runMultiImageTryOn(anyString(), anyString(), anyString(), anyList(), anyString());
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.never())
                .verifyTryOnQuality(any(), anyString(), anyList());
    }

    @Test
    void testPlanItemRemoveAddOnePieceExclusivityAndStyleIntent() throws Exception {
        String demoToken = loginAsDemo();

        MvcResult itemsRes = mockMvc.perform(get("/api/wardrobe/items")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode allItems = objectMapper.readTree(
                itemsRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        Long gothicJskId = null;
        Long extraShoeId = null;
        for (JsonNode n : allItems) {
            String nm = n.path("name").asText("");
            if (nm.contains("暗黑哥特") && nm.contains("JSK")) {
                gothicJskId = n.path("id").asLong();
            }
            if ("shoes".equals(n.path("category").asText("")) && extraShoeId == null) {
                extraShoeId = n.path("id").asLong();
            }
        }
        assertThat(gothicJskId).isNotNull();

        // 1. Style intent "可爱萝莉": must pick sweet Lolita/fairy piece, never gothic JSK or hot-girl pinstripe pants,
        // and must NEVER pair a one-piece dress/JSK with any bottom slot.
        MvcResult lolitaRecRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "nl_query",
                                "userQuery", "可爱萝莉",
                                "weatherTemp", 18,
                                "weatherCondition", "晴"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lolitaPlan = objectMapper.readTree(
                lolitaRecRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        long lolitaPlanId = lolitaPlan.path("id").asLong();
        java.util.List<String> lolitaNames = new java.util.ArrayList<>();
        java.util.List<String> lolitaCats = new java.util.ArrayList<>();
        java.util.List<Long> lolitaItemIds = new java.util.ArrayList<>();
        for (JsonNode slot : lolitaPlan.path("slots")) {
            if (!slot.path("missing").asBoolean(false)) {
                lolitaNames.add(slot.path("item").path("name").asText(""));
                lolitaCats.add(slot.path("slotKey").asText(""));
                lolitaItemIds.add(slot.path("item").path("id").asLong());
            }
        }
        assertThat(String.join(" ", lolitaNames)).containsAnyOf("草莓庄园", "芭蕾千金");
        assertThat(String.join(" ", lolitaNames)).doesNotContain("暗黑哥特");
        assertThat(String.join(" ", lolitaNames)).doesNotContain("灰调细条纹");
        assertThat(lolitaCats).contains("dress", "bag");
        assertThat(lolitaCats).doesNotContain("top", "bottom");

        // 2. Anchor gothic JSK directly: one-piece exclusivity must prevent any bottom or top from being selected
        MvcResult jskRecRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "anchor_item",
                                "anchorItemId", gothicJskId,
                                "weatherTemp", 18
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jskPlan = objectMapper.readTree(
                jskRecRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        java.util.List<String> jskCats = new java.util.ArrayList<>();
        java.util.List<Long> jskIds = new java.util.ArrayList<>();
        for (JsonNode slot : jskPlan.path("slots")) {
            if (!slot.path("missing").asBoolean(false)) {
                jskCats.add(slot.path("slotKey").asText(""));
                jskIds.add(slot.path("item").path("id").asLong());
            }
        }
        assertThat(jskIds).contains(gothicJskId);
        assertThat(jskCats).contains("dress");
        assertThat(jskCats).doesNotContain("top", "bottom");

        // 3. Remove item -> Add item -> Collage sync -> Min 1 item guard -> 40301 IDOR check
        assertThat(lolitaItemIds.size()).isGreaterThanOrEqualTo(2);
        Long removedId = lolitaItemIds.get(lolitaItemIds.size() - 1);

        mockMvc.perform(delete("/api/outfit/plans/" + lolitaPlanId + "/items/" + removedId)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots[?(@.item.id==" + removedId + ")]").isEmpty());

        mockMvc.perform(post("/api/outfit/plans/" + lolitaPlanId + "/items")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("itemId", removedId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots[?(@.item.id==" + removedId + ")]").isNotEmpty());

        // Collage layout sync: keep only first two owned items
        Long keepA = lolitaItemIds.get(0);
        Long keepB = removedId;
        mockMvc.perform(put("/api/outfit/plans/" + lolitaPlanId + "/collage-layout")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nodes", List.of(
                                        Map.of("nodeKey", "k-a", "slotKey", "top", "itemId", keepA, "x", 45, "y", 35, "scale", 1.0, "rotation", 0, "zIndex", 1),
                                        Map.of("nodeKey", "k-b", "slotKey", "shoes", "itemId", keepB, "x", 60, "y", 75, "scale", 0.9, "rotation", 0, "zIndex", 2)
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots.length()").value(2));

        // Remove down to 1 item succeeds, removing last 1 item blocked with 40001
        mockMvc.perform(delete("/api/outfit/plans/" + lolitaPlanId + "/items/" + keepB)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots.length()").value(1));

        mockMvc.perform(delete("/api/outfit/plans/" + lolitaPlanId + "/items/" + keepA)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        // Cross-user IDOR attempt returns 40301
        String otherUsername = "batch2_idor_" + System.currentTimeMillis();
        MvcResult otherReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", otherUsername,
                                "password", "TestPass123!",
                                "nickname", "IDORChecker",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String otherToken = objectMapper.readTree(otherReg.getResponse().getContentAsString())
                .path("data").path("token").asText();

        mockMvc.perform(delete("/api/outfit/plans/" + lolitaPlanId + "/items/" + keepA)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        if (extraShoeId != null) {
            mockMvc.perform(post("/api/outfit/plans/" + lolitaPlanId + "/items")
                            .header("Authorization", "Bearer " + otherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("itemId", extraShoeId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40301));
        }

        // 4. JK + Stockings + Leg Warmers + Sexy hot-girl intent verification: BOTH bag and accessory must appear
        MvcResult jkRecRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "nl_query",
                                "userQuery", "JK制服配黑丝或堆堆袜，纯欲性感辣妹风",
                                "weatherTemp", 20,
                                "weatherCondition", "晴"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jkPlan = objectMapper.readTree(
                jkRecRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        java.util.List<String> jkNames = new java.util.ArrayList<>();
        java.util.List<String> jkCats = new java.util.ArrayList<>();
        for (JsonNode slot : jkPlan.path("slots")) {
            if (!slot.path("missing").asBoolean(false)) {
                jkNames.add(slot.path("item").path("name").asText(""));
                jkCats.add(slot.path("slotKey").asText(""));
            }
        }
        assertThat(jkNames).isNotEmpty();
        assertThat(String.join(" ", jkNames)).containsAnyOf("JK", "水手服", "黑丝", "堆堆袜", "挂脖", "超短裙", "渔网");
        assertThat(jkCats).contains("bag", "accessory");

        // 5. Interview / commute intent: must pick bag and NEVER include stockings/fishnets/leg-warmers
        MvcResult officeRecRes = mockMvc.perform(post("/api/outfit/recommend")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "triggerType", "nl_query",
                                "userQuery", "明天去互联网公司面试，想要干练精神",
                                "weatherTemp", 18,
                                "weatherCondition", "多云"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode officePlan = objectMapper.readTree(
                officeRecRes.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        java.util.List<String> officeNames = new java.util.ArrayList<>();
        java.util.List<String> officeCats = new java.util.ArrayList<>();
        for (JsonNode slot : officePlan.path("slots")) {
            if (!slot.path("missing").asBoolean(false)) {
                officeNames.add(slot.path("item").path("name").asText(""));
                officeCats.add(slot.path("slotKey").asText(""));
            }
        }
        assertThat(officeCats).contains("bag");
        assertThat(String.join(" ", officeNames)).doesNotContain("丝袜", "渔网", "堆堆袜");
    }

    @Test
    void testDecoupledAiConfigIsolationPreservationAndSameHostReuse() throws Exception {
        String uname = "cfg_iso_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", uname,
                                "nickname", "隔离测试",
                                "password", "Pass123456!",
                                "defaultCity", "武汉",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode regData = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data");
        String token = regData.path("token").asText();
        long userId = regData.path("userInfo").path("id").asLong();

        // 1. DeepSeek main preset with no independent image relay -> imageUnsupportedOnMain=true, imageActive=false
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "deepseek",
                                "baseUrl", "https://api.deepseek.com/v1",
                                "apiKey", "sk-ds-main-0001",
                                "visionModel", "deepseek-flash",
                                "chatModel", "deepseek-chat",
                                "imageModel", "gpt-image-1",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visionReady").value(true))
                .andExpect(jsonPath("$.data.imageUnsupportedOnMain").value(true))
                .andExpect(jsonPath("$.data.imageReady").value(false));
        assertThat(aiConfigService.resolveForUser(userId).imageActive()).isFalse();

        // 2. Providing custom imageBaseUrl WITHOUT imageApiKey MUST NEVER leak DeepSeek main key
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "deepseek",
                                "baseUrl", "https://api.deepseek.com/v1",
                                "visionModel", "deepseek-flash",
                                "chatModel", "deepseek-chat",
                                "imageModel", "gpt-image-1",
                                "imageBaseUrl", "https://relay.example.com/v1",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageKeyReusesMain").value(false))
                .andExpect(jsonPath("$.data.imageReady").value(false))
                .andExpect(jsonPath("$.data.imageUnsupportedOnMain").value(false));
        AiConfigService.ResolvedAiCredentials noLeak = aiConfigService.resolveForUser(userId);
        assertThat(noLeak.imageApiKey()).isNull();
        assertThat(noLeak.imageActive()).isFalse();

        // 3. Save independent image key & vision key -> never return plaintext, preserve on blank, support selective clear
        String visKey = "sk-vis-isolated-5555";
        String imgKey = "sk-img-isolated-6666";
        MvcResult bothRes = mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("providerName", "deepseek"),
                                Map.entry("baseUrl", "https://api.deepseek.com/v1"),
                                Map.entry("visionModel", "gpt-4o"),
                                Map.entry("visionBaseUrl", "https://vision.example.com/v1"),
                                Map.entry("visionApiKey", visKey),
                                Map.entry("chatModel", "deepseek-chat"),
                                Map.entry("imageModel", "gpt-image-1"),
                                Map.entry("imageBaseUrl", "https://relay.example.com/v1"),
                                Map.entry("imageApiKey", imgKey),
                                Map.entry("enabled", true)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.hasVisionApiKey").value(true))
                .andExpect(jsonPath("$.data.visionApiKeyMasked").value("sk-v****5555"))
                .andExpect(jsonPath("$.data.hasImageApiKey").value(true))
                .andExpect(jsonPath("$.data.imageApiKeyMasked").value("sk-i****6666"))
                .andExpect(jsonPath("$.data.imageReady").value(true))
                .andReturn();
        assertThat(bothRes.getResponse().getContentAsString()).doesNotContain(visKey, imgKey, "sk-ds-main-0001");

        // Blank re-save preserves all 3 encrypted keys
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "deepseek",
                                "baseUrl", "https://api.deepseek.com/v1",
                                "visionModel", "gpt-4o",
                                "visionBaseUrl", "https://vision.example.com/v1",
                                "chatModel", "deepseek-chat",
                                "imageModel", "gpt-image-1",
                                "imageBaseUrl", "https://relay.example.com/v1",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.hasVisionApiKey").value(true))
                .andExpect(jsonPath("$.data.hasImageApiKey").value(true));

        // 4. Same-host reuse between imageBaseUrl and visionBaseUrl when vision key is cleared
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "deepseek",
                                "baseUrl", "https://api.deepseek.com/v1",
                                "visionModel", "gpt-4o",
                                "visionBaseUrl", "https://relay.example.com/v1",
                                "clearVisionApiKey", true,
                                "chatModel", "deepseek-chat",
                                "imageModel", "gpt-image-1",
                                "imageBaseUrl", "https://relay.example.com/v1",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVisionApiKey").value(false))
                .andExpect(jsonPath("$.data.visionKeyReusesImage").value(true))
                .andExpect(jsonPath("$.data.visionReady").value(true))
                .andExpect(jsonPath("$.data.hasImageApiKey").value(true));
        AiConfigService.ResolvedAiCredentials sameHost = aiConfigService.resolveForUser(userId);
        assertThat(sameHost.visionApiKey()).isEqualTo(imgKey);

        // 5. Clearing only image key leaves main key intact
        mockMvc.perform(put("/api/ai-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "providerName", "deepseek",
                                "baseUrl", "https://api.deepseek.com/v1",
                                "visionModel", "deepseek-flash",
                                "visionBaseUrl", "",
                                "chatModel", "deepseek-chat",
                                "imageModel", "gpt-image-1",
                                "imageBaseUrl", "",
                                "clearImageApiKey", true,
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.hasImageApiKey").value(false))
                .andExpect(jsonPath("$.data.imageUnsupportedOnMain").value(true));
    }
}
