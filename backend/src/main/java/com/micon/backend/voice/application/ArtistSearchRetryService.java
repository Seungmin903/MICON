package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.client.ArtistSearchJobGateway;
import com.micon.backend.voice.domain.VoiceAnalysisJobRepository;
import org.springframework.stereotype.Service;

@Service
public class ArtistSearchRetryService {
    private final ArtistSearchJobService jobs;
    private final ArtistSearchJobGateway gateway;
    private final VoiceAnalysisJobRepository analyses;

    public ArtistSearchRetryService(ArtistSearchJobService jobs, ArtistSearchJobGateway gateway,
                                    VoiceAnalysisJobRepository analyses) {
        this.jobs = jobs;
        this.gateway = gateway;
        this.analyses = analyses;
    }

    public VoiceAnalysisJobResponse retry(long userId, long sessionId, long analysisId) {
        var analysis = analyses.findById(analysisId)
                .filter(item -> item.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        var prepared = jobs.prepareRetry(userId, analysisId);
        if (prepared.freshCommand() != null) {
            try {
                jobs.queued(prepared.jobId(), gateway.submit(prepared.freshCommand()));
                return VoiceAnalysisJobResponse.from(analysis);
            } catch (org.springframework.web.client.ResourceAccessException exception) {
                jobs.dispatchUnknown(prepared.jobId(), exception.getMessage());
                throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
            } catch (RuntimeException exception) {
                jobs.failed(prepared.jobId(), "ARTIST_SEARCH_DISPATCH_FAILED", exception.getMessage());
                throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
            }
        }
        if (prepared.aiJobId() == null || prepared.aiJobId().isBlank()) {
            jobs.failed(prepared.jobId(), "AI_RETRY_UNAVAILABLE", "AI 작업 번호가 없어 재시도할 수 없습니다.");
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        try {
            gateway.retry(prepared.aiJobId());
            return VoiceAnalysisJobResponse.from(analysis);
        } catch (RuntimeException exception) {
            jobs.failed(prepared.jobId(), "AI_RETRY_REJECTED", exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
    }
}
