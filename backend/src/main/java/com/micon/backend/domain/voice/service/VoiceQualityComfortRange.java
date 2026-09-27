package com.micon.backend.domain.voice.service;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** Mirrors the app's 80-point minimum/maximum boundary rule for persisted ranges. */
public final class VoiceQualityComfortRange {
    private VoiceQualityComfortRange() {}
    public record Bounds(BigDecimal low, BigDecimal high) {}

    public static Bounds find(JsonNode quality, BigDecimal low, BigDecimal high) {
        if (quality == null || quality.isNull() || low == null || high == null
                || !"personal-acoustic-v1".equals(quality.path("version").asText())) return null;
        Integer minimum = null, maximum = null;
        for (JsonNode point : quality.path("points")) {
            int midi = point.path("midi").asInt();
            double score = point.path("score").asDouble();
            BigDecimal pitch = BigDecimal.valueOf(midi);
            if (pitch.compareTo(low) < 0 || pitch.compareTo(high) > 0
                    || !Double.isFinite(score) || score < 80 || score > 100) continue;
            minimum = minimum == null ? midi : Math.min(minimum, midi);
            maximum = maximum == null ? midi : Math.max(maximum, midi);
        }
        return minimum == null ? null : new Bounds(BigDecimal.valueOf(minimum), BigDecimal.valueOf(maximum));
    }
}
