package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.application.VoiceAnalysisUploadValidator;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class VoiceAnalysisUploadValidatorTest {
    private final VoiceAnalysisUploadValidator validator = new VoiceAnalysisUploadValidator(262_144_000);

    @Test
    void acceptsHighQualitySongSampleWithFiveValidSegments() {
        validator.validate(file("sample.wav", "audio/wav"), request(44_100, validSegments()));
    }

    @Test
    void rejectsSixteenKhzSongSample() {
        assertThatThrownBy(() -> validator.validate(file("sample.wav", "audio/wav"),
                request(16_000, validSegments())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("44.1kHz");
    }

    @Test
    void rejectsOpusUntilEveryClientUsesTheSameContainerContract() {
        assertThatThrownBy(() -> validator.validate(file("sample.opus", "audio/ogg"),
                request(48_000, validSegments())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WAV");
    }

    @Test
    void rejectsSongSampleWithoutFiveFourSecondSegments() {
        assertThatThrownBy(() -> validator.validate(file("sample.flac", "audio/flac"),
                request(48_000, validSegments().subList(0, 4))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("최소 5개");
    }

    private MockMultipartFile file(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, new byte[] {1, 2, 3});
    }

    private VoiceAnalysisUploadRequest request(int sampleRate,
                                               List<VoiceAnalysisUploadRequest.Segment> segments) {
        return new VoiceAnalysisUploadRequest("request-1", VoiceAnalysisMethod.SONG, 1L, "v1",
                LocalDateTime.now(), 30_000, sampleRate, "BEGINNING", 0, 0,
                "inst-v1", "lyrics-v1", segments);
    }

    private List<VoiceAnalysisUploadRequest.Segment> validSegments() {
        return List.of(
                new VoiceAnalysisUploadRequest.Segment(0, 4_000, 0, 4_000),
                new VoiceAnalysisUploadRequest.Segment(4_000, 8_000, 4_000, 8_000),
                new VoiceAnalysisUploadRequest.Segment(8_000, 12_000, 8_000, 12_000),
                new VoiceAnalysisUploadRequest.Segment(12_000, 16_000, 12_000, 16_000),
                new VoiceAnalysisUploadRequest.Segment(16_000, 20_000, 16_000, 20_000));
    }
}
