package com.wardrobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.service.AiConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.data.promptUsed").isNotEmpty());

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

        // 3. List recent jobs and verify persistence across reloads
        mockMvc.perform(get("/api/outfit/try-on/jobs")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(jobId))
                .andExpect(jsonPath("$.data[0].previewImageUrl").value(previewUrl));

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
}
