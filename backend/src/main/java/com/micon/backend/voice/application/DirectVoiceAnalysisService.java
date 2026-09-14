package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DirectVoiceAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(DirectVoiceAnalysisService.class);
    private final DirectVoiceAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisUploadValidator validator;
    private final VoiceAnalysisJobGateway gateway;
    private final boolean enabled;

    public DirectVoiceAnalysisService(DirectVoiceAnalysisLifecycleService lifecycle,
                                      VoiceAnalysisUploadValidator validator,
                                      VoiceAnalysisJobGateway gateway,
                                      @Value("${ai.voice-analysis-enabled:false}") boolean enabled) {
        this.lifecycle = lifecycle;
        this.validator = validator;
        this.gateway = gateway;
        this.enabled = enabled;
    }

    public VoiceAnalysisJobResponse submit(long userId, long sessionId, MultipartFile file,
                                           VoiceAnalysisUploadRequest request) {
        if (!enabled) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                "음성 분석 서버 연동이 비활성화되어 있습니다.");
        validator.validate(file, request);
        DirectVoiceAnalysisLifecycleService.PreparedJob job =
                lifecycle.createPending(userId, sessionId, file, request);
        if (!"PENDING".equals(job.status())) return job.response();
        try {
            VoiceAnalysisJobGateway.Result result = gateway.submit(new VoiceAnalysisJobGateway.Command(
                    job.analysisId(), file, request));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            return lifecycle.queued(userId, job.analysisId(), result.jobId());
        } catch (RuntimeException exception) {
            lifecycle.dispatchFailed(userId, job.analysisId(), exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
    }

    public VoiceAnalysisJobResponse status(long userId, long sessionId) {
        return lifecycle.latest(userId, sessionId);
    }

    public VoiceAnalysisJobResponse cancel(long userId, long sessionId, long analysisId) {
        DirectVoiceAnalysisLifecycleService.CancelResult result = lifecycle.cancel(userId, sessionId, analysisId);
        if (result.aiJobId() != null && !result.aiJobId().isBlank()) {
            try { gateway.cancel(result.aiJobId()); }
            catch (RuntimeException exception) {
                log.warn("AI cancellation request failed for analysis {}", analysisId, exception);
            }
        }
        return result.response();
    }
}
