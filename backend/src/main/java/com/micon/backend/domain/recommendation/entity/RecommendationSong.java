package com.micon.backend.domain.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "recommendation_song")
public class RecommendationSong {

    @EmbeddedId
    private RecommendationSongId id;

    @MapsId("recommendationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private Recommendation recommendation;

    @MapsId("shelfId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_shelf_id", nullable = false)
    private RecommendationShelf shelf;

    @Column(name = "overall_rank", nullable = false)
    private short overallRank;

    @Column(name = "shelf_position", nullable = false)
    private short shelfPosition;

    @Column(length = 50) private String axis;
    @Column(length = 100) private String label;
    @Column(name = "recommended_key") private Short recommendedKey;
    @Column(name = "final_score", precision = 8, scale = 6) private BigDecimal finalScore;
    @Column(name = "timbre_score", precision = 8, scale = 6) private BigDecimal timbreScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "range_fit", columnDefinition = "json") private String rangeFit;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "json") private String metrics;
    @Column(name = "reason_text", length = 1000) private String reasonText;
    @Column(name = "scoring_version", length = 50) private String scoringVersion;
    @Column(name = "composition_version", length = 50) private String compositionVersion;

    protected RecommendationSong() {
    }

    public RecommendationSong(Recommendation recommendation, RecommendationShelf shelf, String songId,
                              int overallRank, int shelfPosition) {
        if (recommendation == null || recommendation.getId() == null || shelf == null || shelf.getId() == null) {
            throw new IllegalArgumentException("persisted recommendation and shelf are required");
        }
        if (!recommendation.getId().equals(shelf.getRecommendation().getId())) {
            throw new IllegalArgumentException("shelf must belong to recommendation");
        }
        if (songId == null || songId.isBlank() || songId.length() > 64) {
            throw new IllegalArgumentException("songId must be a non-blank string of at most 64 characters");
        }
        if (overallRank < 1 || overallRank > Short.MAX_VALUE) {
            throw new IllegalArgumentException("overallRank must be positive");
        }
        if (shelfPosition < 1 || shelfPosition > 50) {
            throw new IllegalArgumentException("shelfPosition must be between 1 and 50");
        }
        this.id = new RecommendationSongId(recommendation.getId(), shelf.getId(), songId);
        this.recommendation = recommendation;
        this.shelf = shelf;
        this.overallRank = (short) overallRank;
        this.shelfPosition = (short) shelfPosition;
    }

    public RecommendationSongId getId() { return id; }
    public Recommendation getRecommendation() { return recommendation; }
    public RecommendationShelf getShelf() { return shelf; }
    public short getOverallRank() { return overallRank; }
    public short getShelfPosition() { return shelfPosition; }
    public String getAxis() { return axis; }
    public String getLabel() { return label; }
    public Short getRecommendedKey() { return recommendedKey; }
    public BigDecimal getFinalScore() { return finalScore; }
    public BigDecimal getTimbreScore() { return timbreScore; }
    public String getRangeFit() { return rangeFit; }
    public String getMetrics() { return metrics; }
    public String getReasonText() { return reasonText; }
    public String getScoringVersion() { return scoringVersion; }
    public String getCompositionVersion() { return compositionVersion; }

    public void applyResult(String axis, String label, Short recommendedKey, BigDecimal finalScore,
                            BigDecimal timbreScore, String rangeFit, String metrics, String reasonText,
                            String scoringVersion, String compositionVersion) {
        this.axis = axis;
        this.label = label;
        this.recommendedKey = recommendedKey;
        this.finalScore = finalScore;
        this.timbreScore = timbreScore;
        this.rangeFit = rangeFit;
        this.metrics = metrics;
        this.reasonText = reasonText;
        this.scoringVersion = scoringVersion;
        this.compositionVersion = compositionVersion;
    }
}
