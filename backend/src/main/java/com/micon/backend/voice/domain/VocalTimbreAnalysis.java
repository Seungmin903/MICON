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
@Table(name = "vocal_timbre_analysis")
public class VocalTimbreAnalysis {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vocal_timbre_analysis_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voice_analysis_id", nullable = false, unique = true)
    private VoiceAnalysis voiceAnalysis;

    @Column(name = "timbre_label", length = 100) private String timbreLabel;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "timbre_vector", columnDefinition = "json") private String timbreVector;
    @Column(name = "rhythm_score", precision = 5, scale = 2) private BigDecimal rhythmScore;
    @Column(name = "pronunciation_score", precision = 5, scale = 2) private BigDecimal pronunciationScore;
    @Column(precision = 5, scale = 2) private BigDecimal confidence;
    @Column(length = 1000) private String explanation;
    @Column(name = "valid_single_sec", precision = 8, scale = 3) private BigDecimal validSingleSec;
    @Column(name = "valid_chunk_count") private Integer validChunkCount;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "user_embedding", columnDefinition = "json") private String userEmbedding;
    @Column(name = "embedding_ref", length = 1024) private String embeddingRef;
    @Column(name = "chunk_cohesion", precision = 6, scale = 2) private BigDecimal chunkCohesion;
    @Column(name = "observed_core_low_midi", precision = 6, scale = 2) private BigDecimal observedCoreLowMidi;
    @Column(name = "observed_core_high_midi", precision = 6, scale = 2) private BigDecimal observedCoreHighMidi;
    @Column(name = "observed_median_midi", precision = 6, scale = 2) private BigDecimal observedMedianMidi;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "pitch_histogram", columnDefinition = "json") private String pitchHistogram;
    @Column(name = "measured_at", nullable = false) private LocalDateTime measuredAt;

    protected VocalTimbreAnalysis() {
    }
}
