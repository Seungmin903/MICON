package com.micon.backend.domain.voice.service;

import com.micon.backend.domain.voice.repository.VoiceAnalysisComponentJobRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisJobRepository;
import com.micon.backend.domain.voice.repository.VoiceModelResultRepository;
import com.micon.backend.domain.voice.repository.VoiceRangeMeasurementRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.entity.VoiceAnalysisJob;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackResponse;
import com.micon.backend.domain.voice.entity.VoiceAnalysisComponentJob;
import com.micon.backend.domain.voice.entity.VoiceModelResult;
import com.micon.backend.domain.voice.entity.VoiceRangeMeasurement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
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
    private final VoiceRangeMeasurementRepository ranges;
    private final VoiceSessionService sessions;
    private final VoiceAnalysisJobRepository parentJobs;
    private final ArtistSearchCallbackService artistSearchCallbacks;
    private final ApplicationEventPublisher events;

    public VoiceAnalysisComponentCallbackService(VoiceAnalysisComponentJobRepository jobs,
            VoiceModelResultRepository results, ObjectMapper json, VoiceRangeMeasurementRepository ranges,
            VoiceSessionService sessions, VoiceAnalysisJobRepository parentJobs,
            ArtistSearchCallbackService artistSearchCallbacks, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.results = results;
        this.json = json;
        this.ranges = ranges;
        this.sessions = sessions;
        this.parentJobs = parentJobs;
        this.artistSearchCallbacks = artistSearchCallbacks;
        this.events = events;
    }

    @Transactional
    public VoiceAnalysisComponentCallbackResponse accept(VoiceAnalysisComponentCallbackRequest request) {
        VoiceAnalysisComponentCallbackResponse guest = sessions.acceptGuestRangeCallback(request);
        if (guest != null) return guest;
        guest = sessions.acceptGuestTimbreCallback(request);
        if (guest != null) return guest;

        VoiceAnalysisJob parent = parentJobs.findByRequestIdForUpdate(request.requestId()).orElse(null);
        if (parent != null) return acceptTimbre(parent, request);

        VoiceAnalysisComponentJob job = jobs.findByRequestIdForUpdate(request.requestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK));
        bind(job, request.jobId());
        ensureAttempt(job.getAttemptNo(), request.attemptNo());
        if ("ARTIST_SEARCH".equals(job.getComponentType())) {
            if (job.isTerminal()) {
                return new VoiceAnalysisComponentCallbackResponse(
                        job.getAnalysisJob().getId(), job.getStatus(), true);
            }
            artistSearchCallbacks.accept(job, request);
            return new VoiceAnalysisComponentCallbackResponse(
                    job.getAnalysisJob().getId(), job.getStatus(), false);
        }
        if (job.isTerminal()) return response(job, true);
        switch (request.status()) {
            case RUNNING -> job.running(request.processedAt());
            case COMPLETED -> complete(job, request);
            case FAILED -> {
                job.fail(value(request.failureCode(), "AI_ANALYSIS_FAILED"), request.failureReason(),
                        request.processedAt());
                failSession(job, false, request.processedAt());
            }
            case INSUFFICIENT_DATA -> {
                job.insufficient(value(request.failureCode(), "INSUFFICIENT_DATA"), request.failureReason(),
                        request.processedAt());
                failSession(job, true, request.processedAt());
            }
        }
        return response(job, false);
    }

    private VoiceAnalysisComponentCallbackResponse acceptTimbre(
            VoiceAnalysisJob parent, VoiceAnalysisComponentCallbackRequest request) {
        try {
            parent.bindAiJobId(request.jobId());
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
        ensureAttempt(parent.getAttemptNo(), request.attemptNo());
        String componentType = componentType(request);
        List<VoiceAnalysisComponentJob> children = jobs.findByAnalysisJobIdForUpdate(parent.getId());
        VoiceAnalysisComponentJob child = children.stream()
                .filter(job -> job.getComponentType().equals(componentType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK));
        if (child.isTerminal()) return response(parent, true);

        switch (request.status()) {
            case RUNNING -> {
                child.running(request.processedAt());
                parent.running(request.processedAt());
            }
            case COMPLETED -> complete(child, request);
            case FAILED -> child.fail(value(request.failureCode(), "AI_ANALYSIS_FAILED"),
                    request.failureReason(), request.processedAt());
            case INSUFFICIENT_DATA -> child.insufficient(value(request.failureCode(), "INSUFFICIENT_DATA"),
                    request.failureReason(), request.processedAt());
        }
        aggregate(parent, children, request.processedAt());
        return response(parent, false);
    }

    private String componentType(VoiceAnalysisComponentCallbackRequest request) {
        String component = request.component();
        if (component == null || component.isBlank()) component = request.modelKey();
        if (component == null || component.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
        return component;
    }

    private void bind(VoiceAnalysisComponentJob job, String aiJobId) {
        try {
            job.bindAiJobId(aiJobId);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
    }

    private void ensureAttempt(int expected, Integer actual) {
        if (actual == null || actual != expected) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK,
                    "현재 작업의 시도 번호와 일치하지 않습니다.");
        }
    }

    private void failSession(VoiceAnalysisComponentJob job, boolean insufficient, LocalDateTime at) {
        if ("RANGE".equals(job.getComponentType())) job.getSession().failRangeAnalysis(at);
        else job.getSession().failTimbreAnalysis(insufficient, at);
    }

    private void complete(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        if ("RANGE".equals(job.getComponentType())) {
            completeRange(job, request);
            return;
        }
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
                || embedding.stream().anyMatch(item -> item == null || !Double.isFinite(item))
                || !validEnergy(embedding)
                || !validSegments(request.segmentInfo())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK,
                    "모델 키·버전·임베딩 차원·구간 정보가 완료 계약과 일치하지 않습니다.");
        }
        String vectorJson = write(embedding);
        VoiceModelResult previous = results.findBySessionIdAndModelKey(job.getSession().getId(), request.modelKey()).orElse(null);
        if (previous != null) {
            previous.replaceResult(job.getId(), request.modelVersion(), request.dimension(), vectorJson,
                    sha256(vectorJson), write(request.segmentInfo()), writeNullable(request.qualityMetrics()));
            results.save(previous);
        } else results.save(new VoiceModelResult(job.getSession(), job.getId(), request.modelKey(),
                request.modelVersion(), request.dimension(), vectorJson, sha256(vectorJson),
                write(request.segmentInfo()), writeNullable(request.qualityMetrics())));
        job.complete(request.modelKey(), request.modelVersion(), request.processedAt());
        if (job.getAnalysisJob() == null) job.getSession().completeTimbreAnalysis(request.processedAt());
    }

    private void completeRange(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        boolean available = "AVAILABLE".equals(request.measurementStatus());
        if (request.measurementId() == null || request.measurementId().isBlank()
                || !"guided-range-v7".equals(request.protocolVersion())
                || (available && (request.lowMidi() == null || request.highMidi() == null))
                || request.lowMidi() != null && (request.lowMidi().compareTo(java.math.BigDecimal.ZERO) < 0
                || request.lowMidi().compareTo(java.math.BigDecimal.valueOf(127)) > 0)
                || request.highMidi() != null && (request.highMidi().compareTo(java.math.BigDecimal.ZERO) < 0
                || request.highMidi().compareTo(java.math.BigDecimal.valueOf(127)) > 0)
                || request.lowMidi() != null && request.highMidi() != null
                && request.lowMidi().compareTo(request.highMidi()) > 0
                || request.noteResults() == null || request.noteResults().isNull()
                || !java.util.Set.of("AVAILABLE", "INSUFFICIENT_DATA", "FAILED")
                        .contains(request.measurementStatus())
                || !java.util.Set.of("VALID", "NOISY", "CLIPPED", "INSUFFICIENT_VOICE")
                        .contains(request.qualityStatus())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK,
                    "음역 분석 결과가 RANGE 완료 계약과 일치하지 않습니다.");
        }
        if (ranges.findBySessionId(job.getSession().getId()).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK, "이미 저장된 음역 결과가 있습니다.");
        }
        ranges.save(new VoiceRangeMeasurement(job.getSession(), request.measurementId(), request.protocolVersion(),
                "SERVER_AI", request.lowMidi(), request.highMidi(), write(request.noteResults()), request.measurementStatus(),
                request.qualityStatus(), request.processedAt()));
        job.complete("RANGE", value(request.modelVersion(), "guided-range-v7"), request.processedAt());
        job.getSession().acceptRange(request.measurementStatus());
    }

    private boolean validEnergy(List<Double> embedding) {
        double energy = embedding.stream().mapToDouble(item -> item * item).sum();
        return Double.isFinite(energy) && energy >= 1e-12;
    }

    private boolean validSegments(JsonNode segmentInfo) {
        if (segmentInfo == null || !segmentInfo.isObject()) return false;
        JsonNode validChunkCount = segmentInfo.get("validChunkCount");
        return validChunkCount != null && validChunkCount.isIntegralNumber()
                && validChunkCount.asInt() >= 5;
    }

    private void aggregate(VoiceAnalysisJob parent, List<VoiceAnalysisComponentJob> children, LocalDateTime at) {
        if (children.stream().allMatch(job -> "COMPLETED".equals(job.getStatus()))) {
            parent.complete(at);
            parent.getSession().completeTimbreAnalysis(at);
            events.publishEvent(new ArtistSearchRequested(parent.getId()));
            return;
        }
        if (children.stream().allMatch(VoiceAnalysisComponentJob::isTerminal)) {
            boolean insufficient = children.stream()
                    .anyMatch(job -> "INSUFFICIENT_DATA".equals(job.getStatus()));
            parent.fail(insufficient ? "INSUFFICIENT_DATA" : "COMPONENT_FAILED",
                    "하나 이상의 음색 분석 컴포넌트가 완료되지 않았습니다.", at);
            parent.getSession().failTimbreAnalysis(insufficient, at);
            return;
        }
        parent.running(at);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
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

    private VoiceAnalysisComponentCallbackResponse response(VoiceAnalysisJob job, boolean duplicate) {
        return new VoiceAnalysisComponentCallbackResponse(job.getId(), job.getStatus(), duplicate);
    }
}
