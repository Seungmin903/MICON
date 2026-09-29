package com.micon.backend.domain.voice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VoiceQualityContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsV2ReferenceAndLowerRegisterPenaltiesWithoutChangingV1() {
        var valid = mapper.readTree("""
                {"version":"personal-acoustic-v2","referenceMidi":57,"points":[
                  {"midi":55,"score":48,"highChange":0,"registerChange":0.1,"lowIrregularity":0.6,"holdScore":100},
                  {"midi":57,"score":30,"highChange":0,"registerChange":1,"lowIrregularity":0,"holdScore":100},
                  {"midi":59,"score":30,"highChange":0.5,"registerChange":1,"lowIrregularity":0,"holdScore":100}
                ]}
                """);
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(valid, 57)).isTrue();
        var tampered = mapper.readTree(valid.toString().replace("\"score\":30", "\"score\":100"));
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(tampered, 57)).isFalse();
        var missing = mapper.readTree("""
                {"version":"personal-acoustic-v2","referenceMidi":57,"points":[
                  {"midi":57,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100}]}
                """);
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(missing, 57)).isFalse();
    }

    @Test
    void acceptsVersionedMeasuredPointsOnly() {
        var valid = mapper.readTree("""
                {"version":"personal-acoustic-v1","referenceMidi":57,"points":[
                  {"midi":55,"score":62.5,"highChange":0,"lowIrregularity":0.5,"holdScore":100},
                  {"midi":57,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100},
                  {"midi":59,"score":62.5,"highChange":0.5,"lowIrregularity":0,"holdScore":100}
                ]}
                """);
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(valid, 57)).isTrue();
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(valid, 58)).isFalse();
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(valid, null)).isTrue();
    }

    @Test
    void rejectsInventedOrTamperedScoresAndUnsortedPoints() {
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(mapper.readTree("""
                {"version":"personal-acoustic-v1","referenceMidi":57,"points":[
                  {"midi":57,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100},
                  {"midi":55,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100}
                ]}
                """), 57)).isFalse();
        assertThat(VoiceAnalysisUploadValidator.validVoiceQuality(mapper.readTree("""
                {"version":"personal-acoustic-v1","referenceMidi":57,"points":[
                  {"midi":57,"score":99,"highChange":0,"lowIrregularity":0,"holdScore":100}
                ]}
                """), 57)).isFalse();
    }
}
