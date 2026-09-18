package com.micon.backend.recommendation.client;

import com.micon.backend.recommendation.config.RecommendationAiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRecommendationResultGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

    @Test
    void readsAiJobAndStringSongContract() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recommendations/job-123", exchange -> respond(exchange, path, authorization));
        server.start();
        RecommendationAiProperties properties = new RecommendationAiProperties(
                "http://localhost:" + server.getAddress().getPort(), "service-token",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        RecommendationResultGateway.Job result = new HttpRecommendationResultGateway(properties).fetch("job-123");

        assertThat(path.get()).isEqualTo("/recommendations/job-123");
        assertThat(authorization.get()).isEqualTo("Bearer service-token");
        assertThat(result.status()).isEqualTo(RecommendationResultGateway.Status.COMPLETE);
        assertThat(result.result().algorithm().scoringVersion()).isEqualTo("score-v2");
        assertThat(result.result().shelves().getFirst().items().getFirst().songId()).isEqualTo("tj-00042");
        assertThat(result.result().shelves().getFirst().items().getFirst().recommendedKey()).isEqualTo((short) -2);
    }

    private void respond(HttpExchange exchange, AtomicReference<String> path,
                         AtomicReference<String> authorization) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] response = """
                {"id":"job-123","status":"complete","progress":100,"message":"done","error":null,
                 "result":{"algorithm":{"scoring_version":"score-v2","shelves_version":"shelves-v1"},
                 "shelves":[{"key":"voice","title":"내 목소리","metric":"음색",
                 "items":[{"position":1,"song_id":"tj-00042","axis":"CONSENSUS","label":"유사",
                 "timbre_score":0.8,"range_fit":{"score":0.9},"recommended_key":-2,
                 "final_score":0.85,"metrics":{"coverage":0.9},
                 "scoring_version":"score-v2","composition_version":"shelves-v1"}]}]}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
