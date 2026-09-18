package com.micon.backend.domain.voice.client;

import com.micon.backend.domain.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.api.VoiceSessionRequest;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface VoiceAnalysisJobGateway {
    Result submit(Command command);
    Result submitRange(RangeCommand command);
    void cancel(String jobId);
    Result retry(String jobId, String component);

    record Command(long analysisId, long sessionId, Long recordingId, String requestId,
                   int attemptNo, List<String> components, String contractVersion,
                   MultipartFile file, VoiceAnalysisUploadRequest recordingMetadata) {
        public Command(long analysisId, long sessionId, Long recordingId, String requestId,
                       int attemptNo, List<String> components, String contractVersion, MultipartFile file) {
            this(analysisId, sessionId, recordingId, requestId, attemptNo, components, contractVersion, file, null);
        }
    }

    record RangeCommand(long analysisId, long sessionId, MultipartFile file,
                        VoiceSessionRequest.RangeAnalysisUpload metadata) { }

    record Result(String jobId, String status, LocalDateTime audioExpiresAt, boolean duplicate) {
        public Result(String jobId, String status) {
            this(jobId, status, null, false);
        }
    }

    final class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }
}
