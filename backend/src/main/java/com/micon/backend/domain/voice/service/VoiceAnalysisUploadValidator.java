package com.micon.backend.domain.voice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.dto.VoiceSessionRequest;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.entity.VoiceAnalysisMethod;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
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
        boolean v7 = "guided-range-v7".equals(request.protocolVersion())
                && "v1".equals(request.contractVersion())
                && request.attempts() != null && request.attempts().isArray() && !request.attempts().isEmpty()
                && request.sampling() != null && request.sampling().isObject();
        boolean v8 = "guided-range-v8".equals(request.protocolVersion())
                && "v2".equals(request.contractVersion()) && "basic".equals(request.mode())
                && request.parentAnalysisId() == null
                && (request.attempts() == null || request.attempts().isEmpty())
                && (request.sampling() == null || request.sampling().isNull())
                && validFreeStages(request.stages(), request.durationSeconds().doubleValue());
        if (!v7 && !v8) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "음역 측정 메타데이터가 올바르지 않습니다.");
        }
        if (request.voiceQuality() != null && !request.voiceQuality().isNull()
                && (!v8 || !validVoiceQuality(request.voiceQuality(),
                        (int) Math.round(request.stages().get(0).path("endpointMidi").asDouble())))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "기기 발성 점수의 버전 또는 음별 값이 올바르지 않습니다.");
        }
    }

    /** Old clients omit this optional display-only result. It never changes AI range input. */
    static boolean validVoiceQuality(JsonNode quality, Integer expectedReference) {
        if (!quality.isObject() || !"personal-acoustic-v1".equals(quality.path("version").asText())
                || !quality.path("referenceMidi").isIntegralNumber()
                || !quality.path("points").isArray()
                || quality.path("points").isEmpty() || quality.path("points").size() > 49) return false;
        int reference = quality.path("referenceMidi").asInt();
        if (expectedReference != null && reference != expectedReference || reference < 36 || reference > 84) return false;
        int previous = 35;
        boolean foundReference = false;
        for (JsonNode point : quality.path("points")) {
            if (!point.isObject() || !point.path("midi").isIntegralNumber()) return false;
            int midi = point.path("midi").asInt();
            if (midi <= previous || midi > 84) return false;
            previous = midi;
            foundReference |= midi == reference;
            if (!bounded(point, "score", 100) || !bounded(point, "holdScore", 100)
                    || !bounded(point, "highChange", 1) || !bounded(point, "lowIrregularity", 1)) return false;
            double upper = point.path("highChange").asDouble();
            double lower = point.path("lowIrregularity").asDouble();
            if (midi <= reference && upper != 0 || midi >= reference && lower != 0) return false;
            double expectedScore = Math.max(0, Math.min(100, 100 - 75 * upper - 75 * lower))
                    * (.65 + .35 * point.path("holdScore").asDouble() / 100);
            if (Math.abs(expectedScore - point.path("score").asDouble()) > .05) return false;
        }
        return foundReference;
    }

    private static boolean bounded(JsonNode node, String field, double maximum) {
        JsonNode value = node.path(field);
        return value.isNumber() && Double.isFinite(value.asDouble())
                && value.asDouble() >= 0 && value.asDouble() <= maximum;
    }

    // Each sweep stage is a fixed mission; comfort still ends on a held note.
    private static final double MISSION_SECONDS = 15;
    private static final double MAX_STAGE_SECONDS = 45;
    private static final double COMFORT_TIMEOUT_SECONDS = 30;
    private static final double COMFORT_HOLD_SECONDS = 1.5;
    private static final double SWEEP_HOLD_SECONDS = .3;
    private static final double ENDPOINT_OFFSET_SECONDS = .5;

    private boolean validFreeStages(JsonNode stages, double duration) {
        if (stages == null || !stages.isArray() || stages.size() != 3) return false;
        String[] kinds = {"comfort", "down", "up"};
        double previousEnd = 0;
        for (int index = 0; index < 3; index++) {
            JsonNode stage = stages.get(index);
            if (stage == null || !kinds[index].equals(stage.path("kind").asText())) return false;
            for (String field : new String[]{"start", "end", "holdStart", "holdEnd", "startMidi", "endpointMidi"}) {
                if (!stage.path(field).isNumber()) return false;
            }
            double start = stage.path("start").asDouble(), end = stage.path("end").asDouble();
            double holdStart = stage.path("holdStart").asDouble(), holdEnd = stage.path("holdEnd").asDouble();
            double startMidi = stage.path("startMidi").asDouble(), endpointMidi = stage.path("endpointMidi").asDouble();
            boolean sweep = index > 0;
            double length = end - start;
            boolean badLength = sweep
                    ? length < MISSION_SECONDS - .1 || length > MAX_STAGE_SECONDS + .6
                    : length > COMFORT_TIMEOUT_SECONDS + .5;
            double minimumHold = (sweep ? SWEEP_HOLD_SECONDS : COMFORT_HOLD_SECONDS) - .05;
            if (!Double.isFinite(start) || !Double.isFinite(end) || !Double.isFinite(holdStart) || !Double.isFinite(holdEnd)
                    || !Double.isFinite(startMidi) || !Double.isFinite(endpointMidi)
                    || start < previousEnd || badLength || end > duration + .1
                    || holdStart < start || holdEnd > end || holdEnd - holdStart < minimumHold
                    || sweep && holdStart - start < ENDPOINT_OFFSET_SECONDS - .05
                    || startMidi < 36 || startMidi > 84 || endpointMidi < 36 || endpointMidi > 84
                    || index == 1 && startMidi - endpointMidi < 2
                    || index == 2 && endpointMidi - startMidi < 2) return false;
            previousEnd = end;
        }
        return true;
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
