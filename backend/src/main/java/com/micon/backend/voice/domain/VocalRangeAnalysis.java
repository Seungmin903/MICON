package com.micon.backend.voice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "vocal_range_analysis")
public class VocalRangeAnalysis {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vocal_range_analysis_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voice_analysis_id", nullable = false, unique = true)
    private VoiceAnalysis voiceAnalysis;

    @Column(name = "full_low_midi", precision = 6, scale = 2) private BigDecimal fullLowMidi;
    @Column(name = "full_high_midi", precision = 6, scale = 2) private BigDecimal fullHighMidi;
    @Column(name = "stable_low_midi", precision = 6, scale = 2) private BigDecimal stableLowMidi;
    @Column(name = "stable_high_midi", precision = 6, scale = 2) private BigDecimal stableHighMidi;
    @Column(name = "pitch_median_midi", precision = 6, scale = 2) private BigDecimal pitchMedianMidi;
    @Column(name = "pitch_accuracy_score", precision = 5, scale = 2) private BigDecimal pitchAccuracyScore;
    @Column(name = "pitch_stability_score", precision = 5, scale = 2) private BigDecimal pitchStabilityScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "pitch_distribution", columnDefinition = "json") private String pitchDistribution;
    @Column(name = "modal_low_midi", precision = 6, scale = 2) private BigDecimal modalLowMidi;
    @Column(name = "modal_high_midi", precision = 6, scale = 2) private BigDecimal modalHighMidi;
    @Column(name = "falsetto_low_midi", precision = 6, scale = 2) private BigDecimal falsettoLowMidi;
    @Column(name = "falsetto_high_midi", precision = 6, scale = 2) private BigDecimal falsettoHighMidi;
    @Column(name = "high_note_hold_sec", precision = 6, scale = 2) private BigDecimal highNoteHoldSec;
    @Column(precision = 5, scale = 2) private BigDecimal confidence;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "note_results", columnDefinition = "json") private String noteResults;
    @Column(name = "measured_at", nullable = false) private LocalDateTime measuredAt;

    protected VocalRangeAnalysis() {
    }
}
