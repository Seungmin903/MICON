package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.*;
import com.micon.backend.voice.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class VoiceAnalysisReportService {
    private final VoiceAnalysisRepository analyses;
    private final VocalRangeAnalysisRepository ranges;
    private final VocalTimbreAnalysisRepository timbres;
    private final ObjectMapper objectMapper;

    public VoiceAnalysisReportService(VoiceAnalysisRepository analyses, VocalRangeAnalysisRepository ranges,
                                      VocalTimbreAnalysisRepository timbres, ObjectMapper objectMapper) {
        this.analyses = analyses; this.ranges = ranges; this.timbres = timbres; this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisHistoryResponse.Page history(long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var result = analyses.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        return new VoiceAnalysisHistoryResponse.Page(result.getContent().stream()
                .map(VoiceAnalysisHistoryResponse.Summary::from).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisHistoryResponse.Summary latest(long userId) {
        return analyses.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(VoiceAnalysisHistoryResponse.Summary::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisReportResponse report(long userId, long analysisId) {
        VoiceAnalysis analysis = analyses.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        var range = ranges.findByVoiceAnalysisId(analysisId).map(this::range).orElse(null);
        var timbre = timbres.findByVoiceAnalysisId(analysisId).map(this::timbre).orElse(null);
        return new VoiceAnalysisReportResponse(VoiceAnalysisHistoryResponse.Summary.from(analysis), range, timbre);
    }

    private VoiceAnalysisReportResponse.Range range(VocalRangeAnalysis r) {
        return new VoiceAnalysisReportResponse.Range(r.getFullLowMidi(), r.getFullHighMidi(), r.getStableLowMidi(),
                r.getStableHighMidi(), r.getPitchMedianMidi(), r.getPitchAccuracyScore(), r.getPitchStabilityScore(),
                json(r.getPitchDistribution()), r.getModalLowMidi(), r.getModalHighMidi(), r.getFalsettoLowMidi(),
                r.getFalsettoHighMidi(), r.getHighNoteHoldSec(), r.getConfidence(), json(r.getNoteResults()), r.getMeasuredAt());
    }

    private VoiceAnalysisReportResponse.Timbre timbre(VocalTimbreAnalysis t) {
        return new VoiceAnalysisReportResponse.Timbre(t.getTimbreLabel(), t.getRhythmScore(),
                t.getPronunciationScore(), t.getConfidence(), t.getExplanation(), t.getValidSingleSec(),
                t.getValidChunkCount(), t.getChunkCohesion(), t.getObservedCoreLowMidi(),
                t.getObservedCoreHighMidi(), t.getObservedMedianMidi(), json(t.getPitchHistogram()), t.getMeasuredAt());
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try { return objectMapper.readTree(value); }
        catch (JacksonException exception) { throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR); }
    }
}
