package com.wardrobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.service.AiConfigService;
import com.wardrobe.service.SeedDataInitializer;
import com.wardrobe.service.StorageService;
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
        assertThat(items.size()).isGreaterThanOrEqualTo(46);

        Map<String, String> processedByName = new java.util.HashMap<>();
        for (JsonNode item : items) {
            processedByName.put(item.path("name").asText(), item.path("processedImageUrl").asText());
        }
        assertThat(processedByName.get("炭灰毛领修身短款辣妹夹克")).startsWith("/alta/pieces/demo-p");
        assertThat(processedByName.get("极简黑漆皮尖头细跟露跟高跟鞋")).startsWith("/alta/pieces/demo-p");
        assertThat(processedByName.get("灰调细条纹辣妹显瘦微喇长裤")).startsWith("/alta/pieces/demo-p");

        // Re-running backfill must remain completely idempotent (zero duplicate inserts)
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
}
