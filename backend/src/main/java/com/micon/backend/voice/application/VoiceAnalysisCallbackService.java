package com.micon.backend.voice.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisCallbackRequest;
import com.micon.backend.voice.api.VoiceAnalysisCallbackResponse;
import com.micon.backend.voice.domain.*;
import com.micon.backend.voice.storage.VoiceStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

@Service
public class VoiceAnalysisCallbackService {
    private final VoiceAnalysisRepository analyses;
    private final VocalRangeAnalysisRepository ranges;
    private final VocalTimbreAnalysisRepository timbres;
    private final ObjectMapper objectMapper;
    private final VoiceStorage storage;

    public VoiceAnalysisCallbackService(VoiceAnalysisRepository analyses, VocalRangeAnalysisRepository ranges,
                                        VocalTimbreAnalysisRepository timbres, ObjectMapper objectMapper,
                                        VoiceStorage storage) {
        this.analyses = analyses;
        this.ranges = ranges;
        this.timbres = timbres;
        this.objectMapper = objectMapper;
        this.storage = storage;
    }

    @Transactional
    public VoiceAnalysisCallbackResponse accept(VoiceAnalysisCallbackRequest request) {
        VoiceAnalysis analysis = analyses.findByJobIdForUpdate(request.jobId())
                .filter(found -> found.getId().equals(request.voiceAnalysisId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK));
        if (analysis.getStatus() != VoiceAnalysisStatus.ANALYZING) {
            if (isTerminal(analysis.getStatus())) return response(analysis, true);
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
        switch (request.status()) {
            case COMPLETED -> complete(analysis, request);
            case FAILED -> analysis.fail(value(request.failureCode(), "AI_ANALYSIS_FAILED"), request.failureReason(), request.measuredAt());
            case INSUFFICIENT_DATA -> analysis.insufficientData(value(request.failureCode(), "INSUFFICIENT_DATA"),
                    request.failureReason(), request.qualityStatus(), request.measuredAt());
        }
        if (request.status() == VoiceAnalysisCallbackRequest.CallbackStatus.COMPLETED
                || request.status() == VoiceAnalysisCallbackRequest.CallbackStatus.INSUFFICIENT_DATA) {
            deleteOriginalWhenNotKept(analysis.getRecording(), request.measuredAt());
        }
        return response(analysis, false);
    }

    private void deleteOriginalWhenNotKept(VoiceRecording recording, LocalDateTime completedAt) {
        if (recording.isKeepOriginal() || recording.getDeletedAt() != null) {
            return;
        }
        recording.markDeletePending(completedAt);
        try {
            storage.delete(recording.getStoragePath());
            recording.markDeleted(completedAt);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.RECORDING_DELETE_FAILED);
        }
    }

    private void complete(VoiceAnalysis analysis, VoiceAnalysisCallbackRequest request) {
        if (request.range() == null || request.timbre() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var r = request.range(); var t = request.timbre();
        ranges.save(new VocalRangeAnalysis(analysis, r.fullLowMidi(), r.fullHighMidi(), r.stableLowMidi(),
                r.stableHighMidi(), r.pitchMedianMidi(), r.pitchAccuracyScore(), r.pitchStabilityScore(), json(r.pitchDistribution()),
                r.modalLowMidi(), r.modalHighMidi(), r.falsettoLowMidi(), r.falsettoHighMidi(), r.highNoteHoldSec(),
                r.confidence(), json(r.noteResults()), request.measuredAt()));
        timbres.save(new VocalTimbreAnalysis(analysis, t.timbreLabel(), json(t.timbreVector()), t.rhythmScore(),
                t.pronunciationScore(), t.confidence(), t.explanation(), t.validSingleSec(), t.validChunkCount(),
                json(t.userEmbedding()), t.embeddingRef(), t.chunkCohesion(), t.observedCoreLowMidi(),
                t.observedCoreHighMidi(), t.observedMedianMidi(), json(t.pitchHistogram()), request.measuredAt()));
        analysis.complete(request.analysisVersion(), request.qualityStatus(), request.measuredAt());
        events.publishEvent(new VoiceAnalysisCompletedEvent(analysis.getId(), analysis.getUser().getId()));
    }

    private String json(JsonNode value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    }
    private boolean isTerminal(VoiceAnalysisStatus status) {
        return status == VoiceAnalysisStatus.COMPLETED || status == VoiceAnalysisStatus.FAILED
                || status == VoiceAnalysisStatus.CANCELED || status == VoiceAnalysisStatus.INSUFFICIENT_DATA;
    }
    private String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private VoiceAnalysisCallbackResponse response(VoiceAnalysis analysis, boolean duplicate) {
        return new VoiceAnalysisCallbackResponse(analysis.getId(), analysis.getStatus(), duplicate);
    }
}
