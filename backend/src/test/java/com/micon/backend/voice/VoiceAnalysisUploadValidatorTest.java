package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.dto.VoiceSessionRequest;
import com.micon.backend.domain.voice.service.VoiceAnalysisUploadValidator;
import com.micon.backend.domain.voice.entity.VoiceAnalysisMethod;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class VoiceAnalysisUploadValidatorTest {
    private final VoiceAnalysisUploadValidator validator = new VoiceAnalysisUploadValidator(262_144_000);

    @Test
    void acceptsWavAndLeavesSampleQualityValidationToAi() {
        validator.validate(file("sample.wav", "audio/wav"), request(44_100, validSegments()));
        validator.validate(file("sample.wav", "audio/wav"),
                request(16_000, validSegments().subList(0, 4)));
    }

    @Test
    void rejectsFormatsOutsideTheWavContract() {
        assertThatThrownBy(() -> validator.validate(file("sample.opus", "audio/ogg"),
                request(48_000, validSegments())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WAV");
    }

    @Test
    void rejectsWavExtensionWithoutRiffWaveHeader() {
        MockMultipartFile invalid = new MockMultipartFile("file", "sample.wav", "audio/wav",
                new byte[] {1, 2, 3});
        assertThatThrownBy(() -> validator.validate(invalid, request(48_000, validSegments())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RIFF/WAVE");
    }

    @Test
    void acceptsTenSecondLowerSweepButStillRequiresFifteenSecondsForUpperSweep() {
        String stages = """
                [{"kind":"comfort","start":0,"end":3,"startMidi":60,"endpointMidi":60,"holdStart":1,"holdEnd":2.6},
                 {"kind":"down","start":3.2,"end":13.2,"startMidi":60,"endpointMidi":57,"holdStart":7,"holdEnd":7.5},
                 {"kind":"up","start":13.4,"end":28.4,"startMidi":60,"endpointMidi":64,"holdStart":20,"holdEnd":20.5}]
                """;
        validator.validateRange(file("sample.wav", "audio/wav"), freeRangeRequest(stages));
        assertThatThrownBy(() -> validator.validateRange(file("sample.wav", "audio/wav"),
                freeRangeRequest(stages.replace("\"end\":13.2", "\"end\":12.8"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validateRange(file("sample.wav", "audio/wav"),
                freeRangeRequest(stages.replace("\"end\":28.4", "\"end\":28.0"))))
                .isInstanceOf(BusinessException.class);
    }

    private VoiceSessionRequest.RangeAnalysisUpload freeRangeRequest(String stages) {
        return new VoiceSessionRequest.RangeAnalysisUpload("request-1", 1, "measurement-1", LocalDateTime.now(),
                "guided-range-v8", "basic", null, 60, BigDecimal.valueOf(.001), BigDecimal.valueOf(28.5),
                "completed", "endpoint_held", "endpoint_held", null, "v2", null, null,
                new ObjectMapper().readTree(stages));
    }

    private MockMultipartFile file(String name, String contentType) {
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E', 0, 0, 0, 0};
        return new MockMultipartFile("file", name, contentType, wav);
    }

    private VoiceAnalysisUploadRequest request(int sampleRate,
                                               List<VoiceAnalysisUploadRequest.Segment> segments) {
        return new VoiceAnalysisUploadRequest("request-1", VoiceAnalysisMethod.SONG, "1", "v1",
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
