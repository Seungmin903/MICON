package com.micon.backend.domain.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.api.VoiceSessionRequest;
import com.micon.backend.domain.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.domain.VoiceAnalysisMethod;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class VoiceAnalysisUploadValidator {
    private static final Set<String> WAV_MEDIA_TYPES = Set.of("audio/wav", "audio/x-wav", "audio/wave");
    private static final Set<String> START_POSITIONS = Set.of("BEGINNING", "VERSE_2", "HIGHLIGHT");
    private final long maxFileBytes;

    public VoiceAnalysisUploadValidator(
            @Value("${ai.voice-analysis-max-file-bytes:262144000}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public void validate(MultipartFile file, VoiceAnalysisUploadRequest request) {
        String mediaType = file == null ? null : file.getContentType();
        if (file == null || file.isEmpty() || file.getSize() > maxFileBytes
                || mediaType == null || !WAV_MEDIA_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))
                || !"wav".equals(extension(file.getOriginalFilename())) || !hasWavHeader(file)) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "RIFF/WAVE 또는 RF64/WAVE 헤더가 있는 WAV 음성 파일만 제출할 수 있습니다.");
        }
        validateTimeline(request);
    }

    public void validateRange(MultipartFile file, VoiceSessionRequest.RangeAnalysisUpload request) {
        validateFile(file);
        String mediaType = file.getContentType();
        if (!WAV_MEDIA_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))
                || !"wav".equals(extension(file.getOriginalFilename())) || file.getSize() > maxFileBytes
                || !hasWavHeader(file)) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE,
                    "음역 분석에는 WAV 파일만 제출할 수 있습니다.");
        }
        if (!"guided-range-v7".equals(request.protocolVersion())
                || !"v1".equals(request.contractVersion())
                || !request.attempts().isArray() || request.attempts().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "guided-range-v7 음역 측정 메타데이터가 올바르지 않습니다.");
        }
    }

    private void validateFile(MultipartFile file) {
        String mediaType = file == null ? null : file.getContentType();
        if (file == null || file.isEmpty() || file.getSize() > maxFileBytes || mediaType == null) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE, "유효한 음성 파일만 제출할 수 있습니다.");
        }
    }

    private boolean hasWavHeader(MultipartFile file) {
        try (var input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            return header.length == 12
                    && ((header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F')
                    || (header[0] == 'R' && header[1] == 'F' && header[2] == '6' && header[3] == '4'))
                    && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
        } catch (IOException exception) {
            return false;
        }
    }

    private void validateTimeline(VoiceAnalysisUploadRequest request) {
        if (!START_POSITIONS.contains(request.startPositionType())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 녹음 시작 위치입니다.");
        }
        if (request.method() == VoiceAnalysisMethod.SONG
                && (request.songId() == null || request.songId().isBlank() || request.songId().length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "노래 분석에는 64자 이하의 문자열 songId가 필요합니다.");
        }
        validateSegments(request.segments(), request.durationMs());
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
