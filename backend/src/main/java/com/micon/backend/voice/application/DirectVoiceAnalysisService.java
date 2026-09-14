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
    private final TimbreAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisUploadValidator validator;
    private final VoiceAnalysisJobGateway gateway;
    private final boolean enabled;
    private final VoiceSessionService guestSessions;

    public DirectVoiceAnalysisService(TimbreAnalysisLifecycleService lifecycle,
                                      VoiceAnalysisUploadValidator validator,
                                      VoiceAnalysisJobGateway gateway,
                                      VoiceSessionService guestSessions,
                                      @Value("${ai.voice-analysis-enabled:false}") boolean enabled) {
        this.lifecycle = lifecycle;
        this.validator = validator;
        this.gateway = gateway;
        this.guestSessions = guestSessions;
        this.enabled = enabled;
    }

    public VoiceAnalysisJobResponse submit(Long userId, String guestToken, long sessionId, MultipartFile file,
                                           VoiceAnalysisUploadRequest request) {
        if (!enabled) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                "음성 분석 서버 연동이 비활성화되어 있습니다.");
        validator.validate(file, request);
        TimbreAnalysisLifecycleService.Prepared prepared=userId==null?null:lifecycle.create(userId, sessionId, file, request);
        VoiceAnalysisJobResponse pending=userId==null?guestSessions.prepareGuestTimbre(sessionId,guestToken,request.requestId(),1):prepared.response();
        if (!"PENDING".equals(userId==null?pending.status():prepared.status())) return pending;
        try {
            VoiceAnalysisJobGateway.Result result = gateway.submit(new VoiceAnalysisJobGateway.Command(
                    pending.analysisId(), file, request));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            return userId==null?guestSessions.queueGuestTimbre(request.requestId(),result.jobId()):lifecycle.queued(userId, pending.analysisId(), result.jobId());
        } catch (RuntimeException exception) {
            if(userId!=null) lifecycle.unknown(userId, pending.analysisId(), exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
    }

    public VoiceAnalysisJobResponse status(Long userId,String guestToken,long sessionId) {
        return userId==null?guestSessions.guestTimbreStatus(sessionId,guestToken):lifecycle.latest(userId, sessionId);
    }

    public VoiceAnalysisJobResponse cancel(Long userId,String guestToken,long sessionId,long analysisId) {
        if(userId==null)return guestSessions.cancelGuestTimbre(sessionId,guestToken,analysisId);
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
