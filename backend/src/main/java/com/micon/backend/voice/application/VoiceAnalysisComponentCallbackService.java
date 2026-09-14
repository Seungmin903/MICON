package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.voice.api.VoiceAnalysisComponentCallbackResponse;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJobRepository;
import com.micon.backend.voice.domain.VoiceModelResult;
import com.micon.backend.voice.domain.VoiceModelResultRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class VoiceAnalysisComponentCallbackService {
    private final VoiceAnalysisComponentJobRepository jobs;
    private final VoiceModelResultRepository results;
    private final ObjectMapper json;

    public VoiceAnalysisComponentCallbackService(VoiceAnalysisComponentJobRepository jobs,
                                                 VoiceModelResultRepository results,
                                                 ObjectMapper json) {
        this.jobs = jobs;
        this.results = results;
        this.json = json;
    }

    @Transactional
    public VoiceAnalysisComponentCallbackResponse accept(VoiceAnalysisComponentCallbackRequest request) {
        VoiceAnalysisComponentJob job = jobs.findByRequestIdForUpdate(request.requestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK));
        try {
            job.bindAiJobId(request.jobId());
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
        if (job.isTerminal()) return response(job, true);
        switch (request.status()) {
            case RUNNING -> job.running(request.processedAt());
            case COMPLETED -> complete(job, request);
            case FAILED -> {
                job.fail(value(request.failureCode(), "AI_ANALYSIS_FAILED"), request.failureReason(),
                        request.processedAt());
                job.getSession().failTimbreAnalysis(false, request.processedAt());
            }
            case INSUFFICIENT_DATA -> {
                job.insufficient(value(request.failureCode(), "INSUFFICIENT_DATA"), request.failureReason(),
                        request.processedAt());
                job.getSession().failTimbreAnalysis(true, request.processedAt());
            }
        }
        return response(job, false);
    }

    private void complete(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        int expectedDimension = switch (job.getComponentType()) {
            case "ECAPA" -> 192;
            case "SINGER_BYOL" -> 1000;
            default -> throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        };
        List<Double> embedding = request.embedding();
        if (!job.getComponentType().equals(request.modelKey())
                || request.modelVersion() == null || request.modelVersion().isBlank()
                || request.dimension() == null || request.dimension() != expectedDimension
                || embedding == null || embedding.size() != expectedDimension
                || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))
                || request.segmentInfo() == null || request.segmentInfo().isNull()) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK,
                    "모델 키·버전·임베딩 차원·구간 정보가 완료 계약과 일치하지 않습니다.");
        }
        if (results.findBySessionIdAndModelKey(job.getSession().getId(), request.modelKey()).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK,
                    "해당 모델 결과가 이미 저장되어 있습니다.");
        }
        String vectorJson = write(embedding);
        results.save(new VoiceModelResult(job.getSession(), job.getId(), request.modelKey(),
                request.modelVersion(), request.dimension(), vectorJson, sha256(vectorJson),
                write(request.segmentInfo()), writeNullable(request.qualityMetrics())));
        job.complete(request.modelKey(), request.modelVersion(), request.processedAt());
        job.getSession().completeTimbreAnalysis(request.processedAt());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK); }
    }

    private String writeNullable(JsonNode value) {
        return value == null || value.isNull() ? null : write(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private VoiceAnalysisComponentCallbackResponse response(VoiceAnalysisComponentJob job, boolean duplicate) {
        return new VoiceAnalysisComponentCallbackResponse(job.getId(), job.getStatus(), duplicate);
    }
}
