package com.micon.backend.voice.client;

import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.api.VoiceSessionRequest;
import org.springframework.web.multipart.MultipartFile;

public interface VoiceAnalysisJobGateway {
    Result submit(Command command);

    Result submitRange(RangeCommand command);

    void cancel(String jobId);

    record Command(long analysisId, MultipartFile file, VoiceAnalysisUploadRequest metadata) {
    }

    record RangeCommand(long analysisId, long sessionId, MultipartFile file,
                        VoiceSessionRequest.RangeAnalysisUpload metadata) { }

    record Result(String jobId, String status) {
    }
}
