package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DirectVoiceAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(DirectVoiceAnalysisService.class);
    private final TimbreAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisUploadValidator validator;
    private final VoiceAnalysisJobGateway gateway;
    private final VoiceSessionService guestSessions;
    private final boolean enabled;
    private final String contractVersion;

    public DirectVoiceAnalysisService(TimbreAnalysisLifecycleService lifecycle,
                                      VoiceAnalysisUploadValidator validator,
                                      VoiceAnalysisJobGateway gateway,
                                      VoiceSessionService guestSessions,
                                      @Value("${ai.voice-analysis-enabled:false}") boolean enabled,
                                      @Value("${ai.voice-analysis-contract-version:v1}") String contractVersion) {
        this.lifecycle = lifecycle;
        this.validator = validator;
        this.gateway = gateway;
        this.guestSessions = guestSessions;
        this.enabled = enabled;
        this.contractVersion = contractVersion;
    }

    public VoiceAnalysisJobResponse submit(Long userId, String guestToken, long sessionId,
                                           MultipartFile file, VoiceAnalysisUploadRequest request) {
        if (!enabled) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                "음성 분석 서버 연동이 비활성화되어 있습니다.");
        validator.validate(file, request);
        String audioSha256 = sha256(file);
        TimbreAnalysisLifecycleService.Prepared member = userId == null ? null
                : lifecycle.create(userId, sessionId, file, request, audioSha256);
        VoiceAnalysisJobResponse pending = userId == null
                ? guestSessions.prepareGuestTimbre(sessionId, guestToken, request.requestId(), 1, audioSha256)
                : member.response();
        boolean shouldDispatch = userId == null
                ? "PENDING".equals(pending.status()) || "DISPATCH_UNKNOWN".equals(pending.status())
                : member.shouldDispatch();
        if (!shouldDispatch) return pending;

        VoiceAnalysisJobGateway.Result accepted;
        try {
            accepted = gateway.submit(new VoiceAnalysisJobGateway.Command(pending.analysisId(), sessionId,
                    pending.recordingId(), request.requestId(), userId == null ? 1 : member.attemptNo(),
                    TimbreAnalysisLifecycleService.MODEL_COMPONENTS, contractVersion, file, request));
        } catch (RestClientResponseException exception) {
            handleRejection(userId, request.requestId(), pending.analysisId(), exception);
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        } catch (VoiceAnalysisJobGateway.Rejected exception) {
            fail(userId, request.requestId(), pending.analysisId(), "INVALID_AI_ACCEPTANCE", exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        } catch (ResourceAccessException exception) {
            unknown(userId, request.requestId(), pending.analysisId(), exception.getMessage());
            throw uncertain();
        } catch (RuntimeException exception) {
            unknown(userId, request.requestId(), pending.analysisId(), exception.getMessage());
            throw uncertain();
        }
        if (!isAccepted(accepted)) {
            fail(userId, request.requestId(), pending.analysisId(), "INVALID_AI_ACCEPTANCE",
                    "AI가 WAV와 requestId 저장을 확인하는 202 QUEUED 응답을 반환하지 않았습니다.");
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
        return userId == null
                ? guestSessions.queueGuestTimbre(request.requestId(), accepted)
                : lifecycle.queued(userId, pending.analysisId(), accepted);
    }

    public VoiceAnalysisJobResponse status(Long userId, String guestToken, long sessionId) {
        return userId == null ? guestSessions.guestTimbreStatus(sessionId, guestToken)
                : lifecycle.latest(userId, sessionId);
    }

    public VoiceAnalysisJobResponse cancel(Long userId, String guestToken, long sessionId, long analysisId) {
        TimbreAnalysisLifecycleService.CancelResult member = userId == null ? null
                : lifecycle.cancel(userId, sessionId, analysisId);
        VoiceAnalysisJobResponse response = userId == null
                ? guestSessions.cancelGuestTimbre(sessionId, guestToken, analysisId) : member.response();
        String aiJobId = userId == null ? response.aiJobId() : member.aiJobId();
        if (aiJobId != null && !aiJobId.isBlank()) {
            try { gateway.cancel(aiJobId); }
            catch (RuntimeException exception) {
                log.warn("AI cancellation request failed for analysis {}", analysisId, exception);
            }
        }
        return response;
    }

    private boolean isAccepted(VoiceAnalysisJobGateway.Result result) {
        return result != null && result.jobId() != null && !result.jobId().isBlank()
                && result.audioExpiresAt() != null
                && result.status() != null
                && java.util.Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "INSUFFICIENT_DATA").contains(result.status());
    }

    private void handleRejection(Long userId, String requestId, long analysisId,
                                 RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 409) {
            fail(userId, requestId, analysisId, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (status == 410) {
            fail(userId, requestId, analysisId, "AUDIO_EXPIRED", exception.getMessage());
            throw new BusinessException(ErrorCode.AUDIO_EXPIRED);
        }
        fail(userId, requestId, analysisId, "AI_DISPATCH_REJECTED", exception.getMessage());
    }

    private void unknown(Long userId, String requestId, long analysisId, String reason) {
        if (userId == null) guestSessions.timbreDispatchUnknown(requestId, reason);
        else lifecycle.unknown(userId, analysisId, reason);
    }

    private void fail(Long userId, String requestId, long analysisId, String code, String reason) {
        if (userId == null) guestSessions.failGuestTimbre(requestId, code, reason);
        else lifecycle.fail(userId, analysisId, code, reason);
    }

    private BusinessException uncertain() {
        return new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                "AI 접수 여부를 확인할 수 없습니다. 같은 requestId와 WAV로 다시 요청해 주세요.");
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
