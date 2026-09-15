package com.micon.backend.voice.client;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface ArtistSearchJobGateway {

    Result submit(Command command);
    Result retry(String jobId);

    record Command(
            String requestId,
            long analysisId,
            long sessionId,
            String analysisType,
            List<String> components,
            int attemptNo,
            long resultVersion,
            int topK,
            Map<String, ModelResult> modelResults,
            String catalogVersion,
            String scoringVersion,
            String callbackUrl,
            String contractVersion
    ) { }

    record ModelResult(
            String modelVersion,
            int dimension,
            List<Double> embedding
    ) { }

    record Result(
            String jobId,
            String status,
            LocalDateTime audioExpiresAt,
            boolean duplicate
    ) { }

    final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }
}
