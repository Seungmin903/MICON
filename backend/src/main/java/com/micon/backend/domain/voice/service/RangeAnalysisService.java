package com.micon.backend.domain.voice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.dto.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.dto.VoiceSessionRequest;
import com.micon.backend.domain.voice.client.VoiceAnalysisJobGateway;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RangeAnalysisService {
    private final VoiceSessionService sessions;
    private final DirectVoiceAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisUploadValidator validator;
    private final VoiceAnalysisJobGateway gateway;
    private final boolean enabled;

    public RangeAnalysisService(VoiceSessionService sessions,
                                DirectVoiceAnalysisLifecycleService lifecycle,
                                VoiceAnalysisUploadValidator validator,
                                VoiceAnalysisJobGateway gateway,
                                @Value("${ai.voice-analysis-enabled:false}") boolean enabled) {
        this.sessions = sessions;
        this.lifecycle = lifecycle;
        this.validator = validator;
        this.gateway = gateway;
        this.enabled = enabled;
    }

    public VoiceAnalysisJobResponse submit(Long userId, String guestToken, long sessionId,
                                           MultipartFile file, VoiceSessionRequest.RangeAnalysisUpload request) {
        if (!enabled) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                "음성 분석 서버 연동이 비활성화되어 있습니다.");
        validator.validateRange(file, request);
        String audioSha256 = sha256(file);
        VoiceAnalysisJobResponse pending = userId == null
                ? sessions.prepareGuestRange(sessionId, guestToken, request, audioSha256)
                : lifecycle.createRangePending(userId, sessionId, file, request, audioSha256).response();
        if (!"PENDING".equals(pending.status()) && !"DISPATCH_UNKNOWN".equals(pending.status())) return pending;
        try {
            VoiceAnalysisJobGateway.Result accepted = gateway.submitRange(
                    new VoiceAnalysisJobGateway.RangeCommand(pending.analysisId(), sessionId, file, request));
            if (accepted == null || accepted.jobId() == null || accepted.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            return userId == null
                    ? sessions.queueGuestRange(request.requestId(), accepted.jobId())
                    : lifecycle.queuedRange(userId, pending.analysisId(), accepted.jobId());
        } catch (RuntimeException exception) {
            if (userId == null) sessions.rangeDispatchUnknown(request.requestId(), exception.getMessage());
            else lifecycle.rangeDispatchUnknown(userId, pending.analysisId(), exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                    "AI 접수 여부를 확인할 수 없습니다. 같은 requestId와 WAV로 다시 요청해 주세요.");
        }
    }

    private String sha256(MultipartFile file) {
        try (var input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE, "음성 파일을 읽을 수 없습니다.");
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
