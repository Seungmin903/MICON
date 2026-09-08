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

    public VocalRangeAnalysis(VoiceAnalysis voiceAnalysis, BigDecimal fullLowMidi, BigDecimal fullHighMidi,
                              BigDecimal stableLowMidi, BigDecimal stableHighMidi, BigDecimal pitchMedianMidi,
                              BigDecimal pitchAccuracyScore, BigDecimal pitchStabilityScore, String pitchDistribution,
                              BigDecimal modalLowMidi, BigDecimal modalHighMidi, BigDecimal falsettoLowMidi,
                              BigDecimal falsettoHighMidi, BigDecimal highNoteHoldSec, BigDecimal confidence,
                              String noteResults, LocalDateTime measuredAt) {
        this.voiceAnalysis = voiceAnalysis;
        this.fullLowMidi = fullLowMidi; this.fullHighMidi = fullHighMidi;
        this.stableLowMidi = stableLowMidi; this.stableHighMidi = stableHighMidi;
        this.pitchMedianMidi = pitchMedianMidi; this.pitchAccuracyScore = pitchAccuracyScore;
        this.pitchStabilityScore = pitchStabilityScore; this.pitchDistribution = pitchDistribution;
        this.modalLowMidi = modalLowMidi; this.modalHighMidi = modalHighMidi;
        this.falsettoLowMidi = falsettoLowMidi; this.falsettoHighMidi = falsettoHighMidi;
        this.highNoteHoldSec = highNoteHoldSec; this.confidence = confidence;
        this.noteResults = noteResults; this.measuredAt = measuredAt;
    }

    public BigDecimal getFullLowMidi() { return fullLowMidi; }
    public BigDecimal getFullHighMidi() { return fullHighMidi; }
    public BigDecimal getStableLowMidi() { return stableLowMidi; }
    public BigDecimal getStableHighMidi() { return stableHighMidi; }
    public BigDecimal getPitchMedianMidi() { return pitchMedianMidi; }
    public BigDecimal getPitchAccuracyScore() { return pitchAccuracyScore; }
    public BigDecimal getPitchStabilityScore() { return pitchStabilityScore; }
    public String getPitchDistribution() { return pitchDistribution; }
    public BigDecimal getModalLowMidi() { return modalLowMidi; }
    public BigDecimal getModalHighMidi() { return modalHighMidi; }
    public BigDecimal getFalsettoLowMidi() { return falsettoLowMidi; }
    public BigDecimal getFalsettoHighMidi() { return falsettoHighMidi; }
    public BigDecimal getHighNoteHoldSec() { return highNoteHoldSec; }
    public BigDecimal getConfidence() { return confidence; }
    public String getNoteResults() { return noteResults; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
}
