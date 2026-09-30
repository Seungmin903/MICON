package com.micon.backend.voice;

import com.micon.backend.domain.voice.service.VoiceQualityComfortRange;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceQualityComfortRangeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void v2ContinuousScoresUseTheSameComfortRule() {
        var quality = json.readTree("""
            {"version":"personal-acoustic-v2","referenceMidi":60,"points":[
              {"midi":58,"score":85},{"midi":60,"score":65},{"midi":62,"score":30}]}
            """);
        assertThat(VoiceQualityComfortRange.find(quality, BigDecimal.valueOf(58), BigDecimal.valueOf(62)))
                .isEqualTo(new VoiceQualityComfortRange.Bounds(BigDecimal.valueOf(58), BigDecimal.valueOf(58)));
    }

    @Test void usesMinimumAndMaximumMeasuredPitchesAtOrAbove80() {
        var quality = json.readTree("""
            {"version":"personal-acoustic-v1","referenceMidi":60,"points":[
              {"midi":56,"score":90},{"midi":57,"score":80},{"midi":58,"score":0},
              {"midi":59,"score":95},{"midi":60,"score":100},
              {"midi":62,"score":91},{"midi":63,"score":89},{"midi":64,"score":85}]}
            """);
        assertThat(VoiceQualityComfortRange.find(quality, BigDecimal.valueOf(56), BigDecimal.valueOf(64)))
                .isEqualTo(new VoiceQualityComfortRange.Bounds(BigDecimal.valueOf(56), BigDecimal.valueOf(64)));
    }

    @Test void zeroOrMissingScoresNeverCreateComfort() {
        var quality = json.readTree("""
            {"version":"personal-acoustic-v1","referenceMidi":60,"points":[
              {"midi":58,"score":0},{"midi":60,"score":79.9}]}
            """);
        assertThat(VoiceQualityComfortRange.find(quality, BigDecimal.valueOf(58), BigDecimal.valueOf(60))).isNull();
    }
}
