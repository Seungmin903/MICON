package com.micon.backend.voice.client;

import com.micon.backend.voice.api.VoiceSessionRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface VoiceAnalysisJobGateway {
    Result submit(Command command);
    Result submitRange(RangeCommand command);
    void cancel(String jobId);

    record Command(long analysisId, long sessionId, Long recordingId, String requestId,
                   int attemptNo, List<String> components, String contractVersion,
                   MultipartFile file) { }

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
