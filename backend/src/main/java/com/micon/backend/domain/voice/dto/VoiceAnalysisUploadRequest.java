package com.micon.backend.domain.voice.dto;

import com.micon.backend.domain.voice.entity.VoiceAnalysisMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record VoiceAnalysisUploadRequest(
        @Schema(description = "앱이 생성한 UUID 멱등 키", example = "0199f9f5-7e6a-7a20-bc91-23b8c1ed0a11")
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String requestId,
        @Schema(description = "분석 방식. SONG은 추천용 음색 분석이며 고음질 녹음 조건을 적용합니다.")
        @NotNull VoiceAnalysisMethod method,
        @Schema(description = "내부 숫자형 곡 ID. SONG 방식에서는 필수입니다.", example = "21")
        String songId,
        @Schema(description = "녹음 가이드 또는 전처리 계약 버전", example = "v4")
        @NotBlank @Size(max = 50) String guideVersion,
        @Schema(description = "기기에서 녹음을 완료한 시각")
        @NotNull LocalDateTime recordedAt,
        @Schema(description = "기기에서 측정한 활성 녹음 길이(ms). AI가 실제 길이를 최종 검증합니다.", example = "30000")
        @Min(1) int durationMs,
        @Schema(description = "기기에서 기록한 샘플레이트. AI가 실제 샘플레이트를 최종 검증합니다.", example = "48000")
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
