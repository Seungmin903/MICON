package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class VoiceAnalysisUploadValidator {
    private static final Set<String> MEDIA_TYPES = Set.of(
            "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/x-m4a",
            "audio/flac", "audio/ogg", "audio/webm");
    private static final Set<String> EXTENSIONS = Set.of("wav", "mp3", "m4a", "flac", "ogg", "webm");
    private static final Set<String> START_POSITIONS = Set.of("BEGINNING", "VERSE_2", "HIGHLIGHT");
    private final long maxFileBytes;

    public VoiceAnalysisUploadValidator(
            @Value("${ai.voice-analysis-max-file-bytes:262144000}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public void validate(MultipartFile file, VoiceAnalysisUploadRequest request) {
        String mediaType = file == null ? null : file.getContentType();
        String extension = extension(file == null ? null : file.getOriginalFilename());
        if (file == null || file.isEmpty() || file.getSize() > maxFileBytes
                || mediaType == null || !MEDIA_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))
                || !EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "WAV, MP3, M4A, FLAC, OGG, WEBM 형식의 유효한 음성 파일만 제출할 수 있습니다.");
        }
        if (!START_POSITIONS.contains(request.startPositionType())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 녹음 시작 위치입니다.");
        }
        if (request.durationMs() > 120_000 || request.durationMs() < 2_000) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "분석용 녹음 길이는 2초 이상 120초 이하여야 합니다.");
        }
        if (request.method() == VoiceAnalysisMethod.SONG) validateSongSample(request);
        validateSegments(request.segments(), request.durationMs());
    }

    private void validateSongSample(VoiceAnalysisUploadRequest request) {
        if (request.songId() == null || request.songId() < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "노래 분석에는 숫자형 DB songId가 필요합니다.");
        }
        if (request.durationMs() < 30_000 || (request.sampleRate() != 44_100 && request.sampleRate() != 48_000)) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "음색 분석 녹음은 30초 이상이며 44.1kHz 또는 48kHz여야 합니다.");
        }
        List<VoiceAnalysisUploadRequest.Segment> segments = request.segments();
        long valid = segments == null ? 0 : segments.stream()
                .filter(segment -> segment.recordingEndMs() - segment.recordingStartMs() >= 4_000)
                .count();
        if (valid < 5) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "음색 분석에는 4초 이상의 유효 구간이 최소 5개 필요합니다.");
        }
    }

    private void validateSegments(List<VoiceAnalysisUploadRequest.Segment> segments, int durationMs) {
        if (segments == null) return;
        int previousRecordingEnd = -1;
        for (VoiceAnalysisUploadRequest.Segment segment : segments) {
            if (segment.recordingEndMs() <= segment.recordingStartMs()
                    || segment.songEndMs() <= segment.songStartMs()
                    || segment.recordingEndMs() > durationMs
                    || segment.recordingStartMs() < previousRecordingEnd) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "녹음 구간 메타데이터가 올바르지 않습니다.");
            }
            previousRecordingEnd = segment.recordingEndMs();
        }
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
