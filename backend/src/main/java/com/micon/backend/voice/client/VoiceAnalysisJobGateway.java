package com.micon.backend.voice.client;

import com.micon.backend.voice.domain.VoiceAnalysisMethod;

public interface VoiceAnalysisJobGateway {
    Result submit(Command command);

    void cancel(String jobId);

    record Command(long voiceAnalysisId, String recordingUrl, VoiceAnalysisMethod method,
                   Long songId, String guideVersion) {
    }

    record Result(String jobId, String status) {
    }
}
