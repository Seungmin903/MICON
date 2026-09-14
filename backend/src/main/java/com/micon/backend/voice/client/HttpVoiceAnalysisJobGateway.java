package com.micon.backend.voice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class HttpVoiceAnalysisJobGateway implements VoiceAnalysisJobGateway {
    private final RestClient restClient;

    public HttpVoiceAnalysisJobGateway(
            @Value("${ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service-token:}") String serviceToken,
            @Value("${ai.voice-analysis-connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.voice-analysis-read-timeout:30s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.restClient = builder.build();
    }

    @Override
    public Result submit(Command command) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", command.file().getResource())
                .filename(command.file().getOriginalFilename() == null
                        ? "recording" : command.file().getOriginalFilename())
                .contentType(MediaType.parseMediaType(command.file().getContentType()));
        var request = command.metadata();
        body.part("meta", new Metadata(command.analysisId(), request.requestId(), request.method(),
                        request.songId(), request.guideVersion(), request.recordedAt(), request.durationMs(),
                        request.sampleRate(), request.startPositionType(), request.selectedStartMs(),
                        request.actualStartMs(), request.instrumentalVersion(), request.lyricsVersion(),
                        request.segments()))
                .contentType(MediaType.APPLICATION_JSON);
        return restClient.post().uri("/voice-analysis-jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().body(Result.class);
    }

    @Override
    public void cancel(String jobId) {
        restClient.post().uri("/voice-analysis-jobs/{jobId}/cancel", jobId)
                .retrieve().toBodilessEntity();
    }

    private record Metadata(long analysisId, String requestId,
                            com.micon.backend.voice.domain.VoiceAnalysisMethod method,
                            Long songId, String guideVersion, java.time.LocalDateTime recordedAt,
                            int durationMs, int sampleRate, String startPositionType,
                            int selectedStartMs, int actualStartMs, String instrumentalVersion,
                            String lyricsVersion,
                            java.util.List<com.micon.backend.voice.api.VoiceAnalysisUploadRequest.Segment> segments) { }
}
