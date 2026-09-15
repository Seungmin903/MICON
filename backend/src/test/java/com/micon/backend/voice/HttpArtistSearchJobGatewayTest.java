package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.micon.backend.voice.client.ArtistSearchJobGateway;
import com.micon.backend.voice.client.HttpArtistSearchJobGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpArtistSearchJobGatewayTest {

    @Test
    void sendsModelResultsToSearchAiAsJson() throws Exception {
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/artist-search-jobs", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] reply = """
                    {"jobId":"artist-search-job-1","status":"QUEUED","audioExpiresAt":null,"duplicate":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, reply.length);
            try (var response = exchange.getResponseBody()) {
                response.write(reply);
            }
        });
        server.start();

        try {
            var gateway = new HttpArtistSearchJobGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-service-token",
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(3));

            ArtistSearchJobGateway.Result result = gateway.submit(command());

            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertThat(authorization.get()).isEqualTo("Bearer test-service-token");
            assertThat(contentType.get()).startsWith("application/json");
            assertThat(body.get("analysisType").asText()).isEqualTo("ARTIST_SEARCH");
            assertThat(body.get("components").get(0).asText()).isEqualTo("ARTIST_SEARCH");
            assertThat(body.get("modelResults").get("ECAPA").get("dimension").asInt()).isEqualTo(192);
            assertThat(body.get("modelResults").get("SINGER_BYOL").get("dimension").asInt()).isEqualTo(1000);
            assertThat(body.has("songId")).isFalse();
            assertThat(body.has("audio")).isFalse();
            assertThat(result.jobId()).isEqualTo("artist-search-job-1");
            assertThat(result.audioExpiresAt()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnIncompleteModelVectorBeforeCallingAi() {
        var gateway = new HttpArtistSearchJobGateway(
                "http://127.0.0.1:1", "token", Duration.ofMillis(50), Duration.ofMillis(50));
        var invalid = new ArtistSearchJobGateway.Command(
                "0199f9f5-7e6a-7a20-bc91-23b8c1ed0a31", 10, 1,
                "ARTIST_SEARCH", List.of("ARTIST_SEARCH"), 1, 2, 10,
                Map.of(
                        "ECAPA", new ArtistSearchJobGateway.ModelResult("ecapa-v1", 192, List.of(0.1)),
                        "SINGER_BYOL", model("singer-byol-v1", 1000, 0.2)),
                "catalog-v1", "fusion-v2", "https://backend.example.com/callback", "v1");

        assertThatThrownBy(() -> gateway.submit(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArtistSearchJobGateway.Command command() {
        return new ArtistSearchJobGateway.Command(
                "0199f9f5-7e6a-7a20-bc91-23b8c1ed0a31", 10, 1,
                "ARTIST_SEARCH", List.of("ARTIST_SEARCH"), 1, 2, 10,
                Map.of(
                        "ECAPA", model("ecapa-v1", 192, 0.1),
                        "SINGER_BYOL", model("singer-byol-v1", 1000, 0.2)),
                "catalog-v1", "ecapa-singer-byol-absolute-fusion-v2",
                "https://backend.example.com/api/v1/callbacks/voice-analysis-components", "v1");
    }

    private ArtistSearchJobGateway.ModelResult model(String version, int dimension, double value) {
        return new ArtistSearchJobGateway.ModelResult(
                version, dimension, Collections.nCopies(dimension, value));
    }
}
