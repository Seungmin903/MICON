package com.micon.backend.voice.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;
import com.micon.backend.common.observability.AccessLog;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Component
public class HttpVoiceAnalysisJobGateway implements VoiceAnalysisJobGateway {
    private final RestClient restClient;

    public HttpVoiceAnalysisJobGateway(
            @Value("${ai.recommendation.base-url:http://localhost:8788}") String baseUrl,
            @Value("${ai.service-token:}") String serviceToken,
            @Value("${ai.voice-analysis-connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.voice-analysis-read-timeout:30s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new ObservedAiRequestFactory(requestFactory));
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.restClient = builder.build();
    }

    @Override
    public Result submit(Command command) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        addFile(body, command.file());
        body.part("meta", new Metadata(command.analysisId(), command.sessionId(), command.recordingId(),
                        command.requestId(), command.attemptNo(), "TIMBRE", command.components(), command.contractVersion(),
                        command.recordingMetadata()))
                .contentType(MediaType.APPLICATION_JSON);
        var response = restClient.post().uri("/voice-analysis-jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().toEntity(Result.class);
        if (response.getStatusCode() != HttpStatus.ACCEPTED || response.getBody() == null) {
            throw new VoiceAnalysisJobGateway.Rejected(
                    "AI must return 202 after persisting WAV and requestId");
        }
        accepted(command.analysisId(), response.getBody());
        return response.getBody();
    }

    @Override
    public Result submitRange(RangeCommand command) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        addFile(body, command.file());
        var request = command.metadata();
        body.part("meta", new RangeMetadata(command.analysisId(), command.sessionId(), request.requestId(),
                        request.attemptNo(), "RANGE", java.util.List.of("RANGE"), request.measurementId(),
                        request.measuredAt(), request.protocolVersion(), request.mode(), request.parentAnalysisId(),
                        request.startMidi(), request.noiseRms(), request.durationSeconds(), request.stopReason(),
                        request.lowerBoundaryStatus(), request.upperBoundaryStatus(), request.attempts(),
                        request.contractVersion(), request.sampling(), request.registerEvents()))
                .contentType(MediaType.APPLICATION_JSON);
        var response = restClient.post().uri("/voice-analysis-jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().toEntity(Result.class);
        if (response.getStatusCode() != HttpStatus.ACCEPTED || response.getBody() == null
                || response.getBody().audioExpiresAt() == null) {
            throw new VoiceAnalysisJobGateway.Rejected("AI must acknowledge durable range audio with 202");
        }
        accepted(command.analysisId(), response.getBody());
        return response.getBody();
    }

    private void accepted(long analysisId, Result result) {
        AccessLog.event("ai_job_accepted", false, "analysis_id", analysisId,
                "job_ref", result.jobId() == null ? "-" : AccessLog.fingerprint(result.jobId()),
                "status", "accepted");
    }

    private void addFile(MultipartBodyBuilder body, MultipartFile file) {
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(file.getContentType());
        } catch (RuntimeException exception) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        body.part("file", file.getResource())
                .filename(file.getOriginalFilename() == null ? "recording.wav" : file.getOriginalFilename())
                .contentType(contentType);
    }

    @Override
    public void cancel(String jobId) {
        restClient.post().uri("/voice-analysis-jobs/{jobId}/cancel", jobId)
                .retrieve().toBodilessEntity();
    }

    @Override
    public Result retry(String jobId, String component) {
        var response = restClient.post().uri(uri -> uri.path("/voice-analysis-jobs/{jobId}/retry")
                        .queryParam("component", component).build(jobId))
                .retrieve().toEntity(Result.class);
        if (response.getStatusCode() != HttpStatus.ACCEPTED || response.getBody() == null) {
            throw new VoiceAnalysisJobGateway.Rejected("AI did not accept the component retry");
        }
        return response.getBody();
    }

    private record Metadata(long analysisId, long sessionId, Long recordingId, String requestId,
                            int attemptNo, String analysisType, java.util.List<String> components,
                            String contractVersion, com.micon.backend.voice.api.VoiceAnalysisUploadRequest recording) { }

    private record RangeMetadata(long analysisId, long sessionId, String requestId, int attemptNo,
                                 String analysisType, java.util.List<String> components,
                                 String measurementId, java.time.LocalDateTime measuredAt,
                                 String protocolVersion, String mode, Long parentAnalysisId, int startMidi,
                                 java.math.BigDecimal noiseRms, java.math.BigDecimal durationSeconds,
                                 String stopReason, String lowerBoundaryStatus, String upperBoundaryStatus,
                                 tools.jackson.databind.JsonNode attempts, String contractVersion,
                                 tools.jackson.databind.JsonNode sampling,
                                 tools.jackson.databind.JsonNode registerEvents) { }
}
