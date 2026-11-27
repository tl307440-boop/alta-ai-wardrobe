package com.wardrobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wardrobe.model.dto.AiConfigDto;
import com.wardrobe.service.AiConfigService;
import com.wardrobe.service.client.MultimodalLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DecoupledAiRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiConfigService aiConfigService;

    private record CapturedHttpCall(String method, String path, String auth, String body) {}

    @Test
    void testDecoupledRoutingAndMultiChannelConnectivity() throws Exception {
        String uname = "route_test_" + System.currentTimeMillis();
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", uname,
                                "nickname", "路由测试",
                                "password", "Pass123456!",
                                "defaultCity", "武汉",
                                "loadStarterPack", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode regJson = objectMapper.readTree(regRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        String token = regJson.path("token").asText();
        long userId = regJson.path("userInfo").path("id").asLong();

        List<CapturedHttpCall> chatCalls = new CopyOnWriteArrayList<>();
        List<CapturedHttpCall> visionCalls = new CopyOnWriteArrayList<>();
        List<CapturedHttpCall> imageCalls = new CopyOnWriteArrayList<>();

        byte[] tinyPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==");
        String tinyPngB64 = Base64.getEncoder().encodeToString(tinyPng);

        HttpServer chatServer = startMockServer(exchange -> {
            CapturedHttpCall call = readExchange(exchange);
            chatCalls.add(call);
            String reply = "{\"themeTitle\":\"极简通勤\",\"selectedItemIds\":[1],\"missingItems\":[],\"aiReasoning\":\"test\",\"matchScore\":92}";
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":" + objectMapper.writeValueAsString(reply) + "}}]}");
        });

        HttpServer visionServer = startMockServer(exchange -> {
            CapturedHttpCall call = readExchange(exchange);
            visionCalls.add(call);
            String reply = "{\"isClothing\":true,\"name\":\"米白风衣\",\"category\":\"outerwear\",\"subCategory\":\"风衣\",\"color\":\"米白\",\"colorHex\":\"#F5F0E6\",\"season\":\"spring,autumn\",\"thickness\":2,\"styleTags\":[\"极简通勤\"],\"material\":\"纯棉\"}";
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":" + objectMapper.writeValueAsString(reply) + "}}]}");
        });

        HttpServer imageServer = startMockServer(exchange -> {
            CapturedHttpCall call = readExchange(exchange);
            imageCalls.add(call);
            if ("GET".equalsIgnoreCase(call.method()) && call.path().endsWith("/models")) {
                sendJson(exchange, 200, "{\"data\":[{\"id\":\"gpt-image-1\"}]}");
                return;
            }
            sendJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + tinyPngB64 + "\"}]}");
        });

        try {
            String chatBase = "http://127.0.0.1:" + chatServer.getAddress().getPort() + "/v1";
            String visionBase = "http://127.0.0.1:" + visionServer.getAddress().getPort() + "/v1";
            String imageBase = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/v1";

            // Save decoupled config across 3 distinct endpoints & keys
            mockMvc.perform(put("/api/ai-config")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.ofEntries(
                                    Map.entry("providerName", "deepseek"),
                                    Map.entry("baseUrl", chatBase),
                                    Map.entry("apiKey", "sk-chat-secret-1111"),
                                    Map.entry("chatModel", "deepseek-chat"),
                                    Map.entry("visionModel", "gpt-4o"),
                                    Map.entry("visionBaseUrl", visionBase),
                                    Map.entry("visionApiKey", "sk-vision-secret-2222"),
                                    Map.entry("imageModel", "gpt-image-1"),
                                    Map.entry("imageBaseUrl", imageBase),
                                    Map.entry("imageApiKey", "sk-image-secret-3333"),
                                    Map.entry("enabled", true),
                                    Map.entry("vtonEnabled", false)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.visionReady").value(true))
                    .andExpect(jsonPath("$.data.imageReady").value(true))
                    .andExpect(jsonPath("$.data.visionApiKeyMasked").value("sk-v****2222"))
                    .andExpect(jsonPath("$.data.imageApiKeyMasked").value("sk-i****3333"));

            AiConfigService.ResolvedAiCredentials creds = aiConfigService.resolveForUser(userId);
            assertThat(creds.baseUrl()).isEqualTo(chatBase);
            assertThat(creds.apiKey()).isEqualTo("sk-chat-secret-1111");
            assertThat(creds.visionBaseUrl()).isEqualTo(visionBase);
            assertThat(creds.visionApiKey()).isEqualTo("sk-vision-secret-2222");
            assertThat(creds.visionActive()).isTrue();
            assertThat(creds.imageBaseUrl()).isEqualTo(imageBase);
            assertThat(creds.imageApiKey()).isEqualTo("sk-image-secret-3333");
            assertThat(creds.imageActive()).isTrue();

            MultimodalLlmClient client = new MultimodalLlmClient(objectMapper, aiConfigService);
            ReflectionTestUtils.setField(client, "connectTimeoutMs", 3000L);
            ReflectionTestUtils.setField(client, "readTimeoutMs", 8000L);
            ReflectionTestUtils.setField(client, "imageTimeoutMs", 8000L);
            ReflectionTestUtils.setField(client, "dashscopeBaseUrl", chatBase);

            // 1. Verify runtime routing splits requests cleanly across all 3 servers with separate keys
            client.curateOutfitPlan(creds, "test prompt");
            client.recognizeClothingItem(creds, "data:image/png;base64," + tinyPngB64, "photo");
            client.generateEditorialTryOn(creds, "editorial portrait", List.of(), "auto");

            assertThat(chatCalls).hasSize(1);
            assertThat(chatCalls.get(0).auth()).isEqualTo("Bearer sk-chat-secret-1111");
            assertThat(chatCalls.get(0).path()).endsWith("/chat/completions");

            assertThat(visionCalls).hasSize(1);
            assertThat(visionCalls.get(0).auth()).isEqualTo("Bearer sk-vision-secret-2222");
            assertThat(visionCalls.get(0).body()).contains("image_url");

            assertThat(imageCalls).hasSize(1);
            assertThat(imageCalls.get(0).auth()).isEqualTo("Bearer sk-image-secret-3333");
            assertThat(imageCalls.get(0).path()).endsWith("/images/generations");

            // 2. Verify multi-channel connectivity test probes all 3 channels appropriately
            chatCalls.clear();
            visionCalls.clear();
            imageCalls.clear();

            AiConfigDto.TestResultVo testRes = client.testConnection(userId, new AiConfigDto.TestRequest());
            assertThat(testRes.isSuccess()).isTrue();
            assertThat(testRes.getChannels()).hasSize(3);
            assertThat(chatCalls).hasSize(1);
            assertThat(visionCalls).hasSize(1);
            assertThat(visionCalls.get(0).body()).contains("image_url");
            assertThat(imageCalls).hasSize(1);
            assertThat(imageCalls.get(0).method()).isEqualTo("GET");
            assertThat(imageCalls.get(0).path()).endsWith("/models");
        } finally {
            chatServer.stop(0);
            visionServer.stop(0);
            imageServer.stop(0);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer startMockServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
        return server;
    }

    private CapturedHttpCall readExchange(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        return new CapturedHttpCall(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(bodyBytes, StandardCharsets.UTF_8)
        );
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
