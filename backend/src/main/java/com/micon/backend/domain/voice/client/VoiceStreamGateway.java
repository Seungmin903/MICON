package com.micon.backend.domain.voice.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VoiceStreamGateway {
    private final RestClient client;

    public VoiceStreamGateway(@Value("${ai.recommendation.base-url:http://localhost:8788}") String baseUrl,
                              @Value("${ai.service-token:}") String serviceToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new ObservedAiRequestFactory(factory));
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        client = builder.build();
    }

    public State begin(String streamId, long sessionId, int sampleRate) {
        return client.post().uri("/voice-analysis-streams/{id}", streamId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sessionId", sessionId, "sampleRate", sampleRate))
                .retrieve().body(State.class);
    }

    public State status(String streamId, long sessionId) {
        return client.get().uri(uri -> uri.path("/voice-analysis-streams/{id}")
                        .queryParam("sessionId", sessionId).build(streamId))
                .retrieve().body(State.class);
    }

    public State chunk(String streamId, long sessionId, int index, byte[] pcm) {
        return client.put().uri(uri -> uri.path("/voice-analysis-streams/{id}/chunks/{index}")
                        .queryParam("sessionId", sessionId).build(streamId, index))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(pcm)
                .retrieve().body(State.class);
    }

    public Inspected inspect(String streamId, long sessionId, String pcmSha256) {
        return client.post().uri("/voice-analysis-streams/{id}/inspect", streamId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sessionId", sessionId, "pcmSha256", pcmSha256))
                .retrieve().body(Inspected.class);
    }

    public VoiceAnalysisJobGateway.Result complete(String streamId, String pcmSha256, long analysisId,
            long sessionId, Long recordingId, int attemptNo, String contractVersion,
            VoiceAnalysisUploadRequest recording) {
        Metadata metadata = new Metadata(analysisId, sessionId, recordingId, streamId, attemptNo,
                "TIMBRE", List.of("ECAPA", "SINGER_BYOL"), contractVersion, recording);
        return client.post().uri("/voice-analysis-streams/{id}/complete", streamId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("meta", metadata, "pcmSha256", pcmSha256))
                .retrieve().body(VoiceAnalysisJobGateway.Result.class);
    }

    public record State(String streamId, long sessionId, int sampleRate,
                        int nextIndex, long receivedBytes, boolean completed) { }
    public record Inspected(String streamId, long sessionId, int sampleRate, int nextIndex,
                            long receivedBytes, boolean completed, String pcmSha256,
                            long sampleCount, int durationMs) { }
    private record Metadata(long analysisId, long sessionId, Long recordingId, String requestId,
                            int attemptNo, String analysisType, List<String> components,
                            String contractVersion, VoiceAnalysisUploadRequest recording) { }
}
