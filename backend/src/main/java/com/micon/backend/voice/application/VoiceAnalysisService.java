package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceAnalysisRequest;
import com.micon.backend.voice.api.VoiceAnalysisResponse;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.storage.VoiceStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class VoiceAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(VoiceAnalysisService.class);
    private final VoiceAnalysisRepository analyses;
    private final VoiceAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisJobGateway aiGateway;
    private final VoiceStorage storage;
    private final String guideVersion;
    private final Duration downloadUrlTtl;

    public VoiceAnalysisService(VoiceAnalysisRepository analyses, VoiceAnalysisLifecycleService lifecycle,
                                VoiceAnalysisJobGateway aiGateway, VoiceStorage storage,
                                @Value("${ai.voice-analysis-guide-version:v1}") String guideVersion,
                                @Value("${ai.voice-analysis-download-url-ttl:30m}") Duration downloadUrlTtl) {
        this.analyses = analyses;
        this.lifecycle = lifecycle;
        this.aiGateway = aiGateway;
        this.storage = storage;
        this.guideVersion = guideVersion;
        this.downloadUrlTtl = downloadUrlTtl;
    }

    public VoiceAnalysisResponse create(long userId, VoiceAnalysisRequest request) {
        VoiceAnalysis analysis = lifecycle.create(userId, request);
        return dispatch(analysis);
    }

    public VoiceAnalysisResponse retry(long userId, long analysisId) {
        return dispatch(lifecycle.prepareRetry(userId, analysisId));
    }

    public VoiceAnalysisResponse cancel(long userId, long analysisId) {
        String jobId = lifecycle.cancel(userId, analysisId);
        if (jobId != null && !jobId.isBlank()) {
            try {
                aiGateway.cancel(jobId);
            } catch (RuntimeException exception) {
                log.warn("AI cancellation request failed for voice analysis {}", analysisId, exception);
            }
        }
        return get(userId, analysisId);
    }

    private VoiceAnalysisResponse dispatch(VoiceAnalysis analysis) {
        try {
            String url = storage.createDownloadUrl(analysis.getRecording().getStoragePath(), downloadUrlTtl).toString();
            VoiceAnalysisJobGateway.Result result = aiGateway.submit(new VoiceAnalysisJobGateway.Command(
                    analysis.getId(), url, analysis.getMethod(), analysis.getSongId(), guideVersion));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            return VoiceAnalysisResponse.from(lifecycle.start(analysis.getId(), result.jobId()));
        } catch (RuntimeException exception) {
            lifecycle.fail(analysis.getId(), exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisResponse get(long userId, long analysisId) {
        VoiceAnalysis analysis = analyses.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (analysis.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return VoiceAnalysisResponse.from(analysis);
    }
}
