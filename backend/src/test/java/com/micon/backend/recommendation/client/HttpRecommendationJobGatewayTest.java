package com.micon.backend.recommendation.client;

import com.micon.backend.domain.recommendation.service.RecommendationInput;
import com.micon.backend.domain.recommendation.client.HttpRecommendationJobGateway;
import com.micon.backend.domain.recommendation.client.RecommendationJobGateway;
import com.micon.backend.domain.recommendation.client.RecommendationQueueFullException;
import com.micon.backend.domain.recommendation.config.RecommendationAiProperties;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.web.client.HttpServerErrorException;

class HttpRecommendationJobGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsStoredAnalysisAsJsonAndReadsAcceptedJob() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recommendations",
                exchange -> respond(exchange, authorization, contentType, requestBody));
        server.start();
        ObjectMapper objectMapper = new ObjectMapper();
        RecommendationAiProperties properties = new RecommendationAiProperties(
                "http://localhost:" + server.getAddress().getPort(), "service-token",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        HttpRecommendationJobGateway gateway = new HttpRecommendationJobGateway(properties, objectMapper);

        RecommendationJobGateway.Result result = gateway.submit(new RecommendationJobGateway.Command(
                input(), RecommendationMode.RANGE, 50));
        JsonNode body = objectMapper.readTree(requestBody.get());

        assertThat(result).isEqualTo(new RecommendationJobGateway.Result("job-123", "queued"));
        assertThat(authorization.get()).isEqualTo("Bearer service-token");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(body.get("profileId").asLong()).isEqualTo(10L);
        assertThat(body.get("sourceSongId").asLong()).isEqualTo(30L);
        assertThat(body.get("topK").asInt()).isEqualTo(50);
        assertThat(body.get("mode").asText()).isEqualTo("range");
        assertThat(body.get("genres").get(0).asText()).isEqualTo("발라드");
        assertThat(body.get("preferredArtists").get(0).asText()).isEqualTo("아이유");
        assertThat(body.get("userEmbedding").get("ecapa").get(0).asDouble()).isEqualTo(0.1);
        assertThat(body.get("voiceProfile").get("pitchHistogram").get("52").asDouble()).isEqualTo(0.4);
        assertThat(body.get("voiceProfile").get("voiceQuality").get("points").get(0).get("score").asInt())
                .isEqualTo(30);
        assertThat(body.get("voiceProfile").get("voiceQuality").get("version").asText())
                .isEqualTo("personal-acoustic-v2");
        assertThat(body.get("voiceProfile").get("voiceQuality").get("points").get(0).get("registerChange").asDouble())
                .isEqualTo(1.0);
    }

    @Test
    void onlyQueueCapacity503IsSafeToRetry() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recommendations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = "{\"detail\":\"추천 처리 대기열이 가득 찼습니다. 잠시 후 다시 요청해 주세요.\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var properties = new RecommendationAiProperties(
                "http://localhost:" + server.getAddress().getPort(), "", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var gateway = new HttpRecommendationJobGateway(properties, new ObjectMapper());

        assertThatThrownBy(() -> gateway.submit(new RecommendationJobGateway.Command(
                input(), RecommendationMode.OVERALL, 50)))
                .isInstanceOf(RecommendationQueueFullException.class);
    }

    @Test
    void catalogUnavailable503IsNotMistakenForAFullQueue() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recommendations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"detail\":\"DB song_id 카탈로그 연결이 준비되지 않았습니다.\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var properties = new RecommendationAiProperties(
                "http://localhost:" + server.getAddress().getPort(), "", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var gateway = new HttpRecommendationJobGateway(properties, new ObjectMapper());

        assertThatThrownBy(() -> gateway.submit(new RecommendationJobGateway.Command(
                input(), RecommendationMode.OVERALL, 50)))
                .isInstanceOf(HttpServerErrorException.class)
                .isNotInstanceOf(RecommendationQueueFullException.class);
    }

    private void respond(HttpExchange exchange, AtomicReference<String> authorization,
                         AtomicReference<String> contentType, AtomicReference<String> requestBody) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"id\":\"job-123\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(202, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private RecommendationInput input() {
        return new RecommendationInput(
                10L,
                20L, 1L,
                "30",
                new RecommendationInput.VoiceProfile(
                        null, null, null, "{\"bins\":[]}", null, "맑은 음색",
                        "{\"bright\":0.8}", null,
                        "{\"ecapa\":[0.1],\"singerByol\":[0.2]}", "embeddings/user-1.json",
                        null, null, null, "{\"52\":0.4}",
                        "{\"version\":\"personal-acoustic-v2\",\"referenceMidi\":60,\"points\":[{\"midi\":60,\"score\":30,\"registerChange\":1,\"highChange\":0,\"lowIrregularity\":0,\"holdScore\":100}]}"),
                List.of("발라드", "록"),
                List.of("아이유"));
    }
}
