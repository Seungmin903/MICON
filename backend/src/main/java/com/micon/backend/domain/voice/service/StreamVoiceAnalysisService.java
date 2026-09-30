package com.micon.backend.domain.voice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.domain.voice.client.VoiceStreamGateway;
import com.micon.backend.domain.voice.dto.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class StreamVoiceAnalysisService {
    private final VoiceSessionService sessions;
    private final TimbreAnalysisLifecycleService lifecycle;
    private final VoiceAnalysisUploadValidator validator;
    private final VoiceStreamGateway ai;
    private final boolean enabled;
    private final boolean streamEnabled;
    private final String contractVersion;

    public StreamVoiceAnalysisService(VoiceSessionService sessions, TimbreAnalysisLifecycleService lifecycle,
            VoiceAnalysisUploadValidator validator, VoiceStreamGateway ai,
            @Value("${ai.voice-analysis-enabled:false}") boolean enabled,
            @Value("${ai.voice-analysis-stream-enabled:true}") boolean streamEnabled,
            @Value("${ai.voice-analysis-contract-version:v1}") String contractVersion) {
        this.sessions = sessions; this.lifecycle = lifecycle; this.validator = validator; this.ai = ai;
        this.enabled = enabled; this.streamEnabled = streamEnabled; this.contractVersion = contractVersion;
    }

    public VoiceStreamGateway.State begin(Long userId, String guestToken, long sessionId,
                                          String streamId, int sampleRate) {
        require(userId, guestToken, sessionId, streamId);
        if (sampleRate != 44100 && sampleRate != 48000) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        try { return ai.begin(streamId, sessionId, sampleRate); }
        catch (RestClientResponseException | ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                    "스트리밍 AI 경로를 사용할 수 없습니다. 전체 WAV 업로드를 사용하세요.");
        }
    }

    public VoiceStreamGateway.State status(Long userId, String guestToken, long sessionId, String streamId) {
        require(userId, guestToken, sessionId, streamId);
        return ai.status(streamId, sessionId);
    }

    public VoiceStreamGateway.State chunk(Long userId, String guestToken, long sessionId,
                                          String streamId, int index, byte[] pcm) {
        require(userId, guestToken, sessionId, streamId);
        if (pcm == null || pcm.length == 0 || pcm.length > 192000 || pcm.length % 2 != 0) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE);
        }
        return ai.chunk(streamId, sessionId, index, pcm);
    }

    public VoiceAnalysisJobResponse complete(Long userId, String guestToken, long sessionId,
            String streamId, String pcmSha256, VoiceAnalysisUploadRequest recording) {
        require(userId, guestToken, sessionId, streamId);
        if (recording == null || !streamId.equals(recording.requestId())
                || pcmSha256 == null || !pcmSha256.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        VoiceAnalysisJobResponse existing = null;
        try {
            existing = userId == null ? sessions.guestTimbreStatus(sessionId, guestToken)
                    : lifecycle.latest(userId, sessionId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.VOICE_ANALYSIS_NOT_FOUND) throw exception;
            // No analysis has been created yet; the draft must be inspected and committed.
        }
        if (existing != null && streamId.equals(existing.requestId()) && existing.aiJobId() != null) {
            return existing;
        }
        VoiceStreamGateway.Inspected inspected = ai.inspect(streamId, sessionId, pcmSha256);
        if (inspected == null) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        validator.validateStream(recording, inspected.receivedBytes(), inspected.sampleRate(), inspected.durationMs());
        TimbreAnalysisLifecycleService.Prepared member = userId == null ? null
                : lifecycle.createStream(userId, sessionId, recording, pcmSha256, inspected.receivedBytes() + 44);
        VoiceAnalysisJobResponse pending = userId == null
                ? sessions.prepareGuestTimbre(sessionId, guestToken, streamId, 1, pcmSha256)
                : member.response();
        boolean shouldDispatch = userId == null
                ? "PENDING".equals(pending.status()) || "DISPATCH_UNKNOWN".equals(pending.status())
                : member.shouldDispatch();
        if (!shouldDispatch) return pending;
        VoiceAnalysisJobGateway.Result accepted;
        try {
            accepted = ai.complete(streamId, pcmSha256, pending.analysisId(), sessionId,
                    pending.recordingId(), userId == null ? 1 : member.attemptNo(), contractVersion, recording);
        } catch (ResourceAccessException exception) {
            unknown(userId, streamId, pending.analysisId(), exception.getMessage());
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED,
                    "AI 접수 여부를 확인할 수 없습니다. 같은 streamId로 상태를 조회한 뒤 재시도하세요.");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
        if (accepted == null || accepted.jobId() == null || accepted.audioExpiresAt() == null) {
            unknown(userId, streamId, pending.analysisId(), "AI의 접수 응답이 불완전합니다.");
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        }
        return userId == null ? sessions.queueGuestTimbre(streamId, accepted)
                : lifecycle.queued(userId, pending.analysisId(), accepted);
    }

    private void unknown(Long userId, String requestId, long analysisId, String reason) {
        if (userId == null) sessions.timbreDispatchUnknown(requestId, reason);
        else lifecycle.unknown(userId, analysisId, reason);
    }

    private void require(Long userId, String guestToken, long sessionId, String streamId) {
        if (!enabled || !streamEnabled) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED);
        try { UUID.fromString(streamId); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        sessions.requireAccessible(userId, guestToken, sessionId);
    }
}
