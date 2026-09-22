package com.micon.backend.domain.songfit.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.domain.songfit.dto.SongFitResponse;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpSongFitGatewayTest {

    @Test
    void sendsCompleteJsonSongFitPayload() throws Exception {
        var contentType = new AtomicReference<String>();
        var accept = new AtomicReference<String>();
        var payload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/song-fit", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                    {"songId":"10","analysisId":20,"selectedKeyOffset":-2,"evaluationStatus":"AVAILABLE",
                     "originalKeyFit":null,"selectedKeyFit":null,"recommendedKeyOffset":-2,
                     "recommendationScores":null,"reason":"AI 계산","burdenSections":[],"unavailableReason":null}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (var body = exchange.getResponseBody()) {
                body.write(reply);
            }
        });
        server.start();
        try {
            var gateway = new HttpSongFitGateway("http://127.0.0.1:" + server.getAddress().getPort(), "");
            SongFitResponse result = gateway.evaluate(new SongFitGateway.Command(
                    7L, "10", 20L, 30L, -2,
                    new BigDecimal("55"), new BigDecimal("75"),
                    new BigDecimal("50"), new BigDecimal("70"), -2,
                    null, "음역", List.of()));

            assertThat(contentType.get()).startsWith("application/json");
            assertThat(accept.get()).contains("application/json");
            assertThat(payload.get())
                    .contains("\"userStableLowMidi\":55")
                    .contains("\"userStableHighMidi\":75")
                    .contains("\"songLowMidi\":50")
                    .contains("\"songHighMidi\":70")
                    .contains("\"selectedKeyOffset\":-2");
            assertThat(result.songId()).isEqualTo("10");
        } finally {
            server.stop(0);
        }
    }
}
