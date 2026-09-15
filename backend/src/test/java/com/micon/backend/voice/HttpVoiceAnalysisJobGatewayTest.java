package com.micon.backend.voice;

import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.api.VoiceSessionRequest;
import com.micon.backend.voice.client.HttpVoiceAnalysisJobGateway;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class HttpVoiceAnalysisJobGatewayTest {
    @Test void multipartCarriesRangeEvidenceAndTimbreRecordingTimeline() throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/voice-analysis-jobs", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] reply = """
                    {"jobId":"job_wire","status":"QUEUED","audioExpiresAt":"2026-09-16T00:00:00","duplicate":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, reply.length);
            try (var body = exchange.getResponseBody()) { body.write(reply); }
        });
        server.start();
        try {
            var gateway = new HttpVoiceAnalysisJobGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-service-token", Duration.ofSeconds(3), Duration.ofSeconds(3));
            var wav = new MockMultipartFile("file", "voice.wav", "audio/wav", new byte[]{'R','I','F','F'});
            var range = json.readValue(Files.readString(Path.of("../docs/contracts/onboarding-range-v7.json")),
                    VoiceSessionRequest.RangeAnalysisUpload.class);
            assertThat(gateway.submitRange(new VoiceAnalysisJobGateway.RangeCommand(9, 7, wav, range)).jobId())
                    .isEqualTo("job_wire");
            JsonNode meta = metadata(received.get(), json);
            assertThat(authorization.get()).isEqualTo("Bearer test-service-token");
            assertThat(meta.get("analysisType").asText()).isEqualTo("RANGE");
            assertThat(meta.get("sampling").get("strategy").asText()).isEqualTo("adaptive_6");
            assertThat(meta.get("registerEvents").get(0).get("register").asText()).isEqualTo("modal");
            assertThat(meta.get("analysisId").asLong()).isEqualTo(9);
            assertThat(received.get()).contains("filename=\"voice.wav\"").contains("RIFF");

            var recording = json.readValue("""
                    {"requestId":"0199f9f5-0000-4000-8000-000000000002","method":"SONG","songId":21,
                     "guideVersion":"v4","recordedAt":"2026-09-15T01:00:00","durationMs":30000,"sampleRate":48000,
                     "startPositionType":"HIGHLIGHT","selectedStartMs":10000,"actualStartMs":11000,
                     "instrumentalVersion":"v1","lyricsVersion":"v1",
                     "segments":[{"recordingStartMs":0,"recordingEndMs":30000,"songStartMs":11000,"songEndMs":41000}]}
                    """, VoiceAnalysisUploadRequest.class);
            gateway.submit(new VoiceAnalysisJobGateway.Command(10, 7, null, recording.requestId(), 1,
                    List.of("ECAPA", "SINGER_BYOL"), "v1", wav, recording));
            meta = metadata(received.get(), json);
            assertThat(meta.get("analysisType").asText()).isEqualTo("TIMBRE");
            assertThat(meta.get("recording").get("sampleRate").asInt()).isEqualTo(48000);
            assertThat(meta.get("recording").get("segments").get(0).get("songStartMs").asInt()).isEqualTo(11000);
        } finally { server.stop(0); }
    }
    private JsonNode metadata(String body, ObjectMapper json) {
        int part = body.indexOf("name=\"meta\"");
        int start = body.indexOf("\r\n\r\n", part) + 4;
        return json.readTree(body.substring(start, body.indexOf("\r\n--", start)));
    }
}
