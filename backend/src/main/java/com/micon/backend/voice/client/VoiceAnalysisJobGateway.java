package com.micon.backend.voice.client;

import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import org.springframework.web.multipart.MultipartFile;

public interface VoiceAnalysisJobGateway {
    Result submit(Command command);

    void cancel(String jobId);

    record Command(long analysisId, MultipartFile file, VoiceAnalysisUploadRequest metadata) {
    }

    record Result(String jobId, String status) {
    }
}
