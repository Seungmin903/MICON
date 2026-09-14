package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record VoiceAnalysisUploadRequest(
        @Schema(description = "앱이 생성한 중복 요청 방지 식별자", example = "voice-20260914-001")
        @NotBlank @Size(max = 100) String requestId,
        @Schema(description = "분석 방식. SONG은 추천용 음색 분석이며 고음질 녹음 조건을 적용합니다.")
        @NotNull VoiceAnalysisMethod method,
        @Schema(description = "내부 숫자형 곡 ID. SONG 방식에서는 필수입니다.", example = "21")
        Long songId,
        @Schema(description = "녹음 가이드 또는 전처리 계약 버전", example = "v4")
        @NotBlank @Size(max = 50) String guideVersion,
        @Schema(description = "기기에서 녹음을 완료한 시각")
        @NotNull LocalDateTime recordedAt,
        @Schema(description = "실제 활성 녹음 길이(ms). 최대 120초", example = "30000")
        @Min(1) int durationMs,
        @Schema(description = "원본 녹음 샘플레이트. SONG은 44100 또는 48000", example = "48000")
        @Min(1) int sampleRate,
        @NotBlank @Size(max = 30) String startPositionType,
        @Min(0) int selectedStartMs,
        @Min(0) int actualStartMs,
        @NotBlank @Size(max = 100) String instrumentalVersion,
        @NotBlank @Size(max = 100) String lyricsVersion,
        @Valid List<Segment> segments
) {
    public record Segment(
            @Min(0) int recordingStartMs,
            @Min(1) int recordingEndMs,
            @Min(0) int songStartMs,
            @Min(1) int songEndMs
    ) { }
}
