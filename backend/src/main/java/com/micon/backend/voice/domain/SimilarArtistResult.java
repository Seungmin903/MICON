package com.micon.backend.voice.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "similar_artist_result")
public class SimilarArtistResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "similar_artist_result_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @Column(name = "component_job_id", nullable = false) private Long componentJobId;
    @Column(name = "singer_id", nullable = false) private Long singerId;
    @Column(name = "rank_no", nullable = false) private short rankNo;
    @Column(name = "cosine_similarity", nullable = false, precision = 8, scale = 6) private BigDecimal cosineSimilarity;
    @Column(name = "ecapa_score", precision = 8, scale = 6) private BigDecimal ecapaScore;
    @Column(name = "singer_byol_score", precision = 8, scale = 6) private BigDecimal singerByolScore;
    @Column(name = "catalog_batch_id", nullable = false, length = 64) private String catalogBatchId;
    protected SimilarArtistResult() {}
    public SimilarArtistResult(VoiceAnalysisSession session, Long componentJobId, Long singerId,
                               short rankNo, BigDecimal cosineSimilarity, BigDecimal ecapaScore,
                               BigDecimal singerByolScore, String catalogBatchId) {
        this.session = session;
        this.componentJobId = componentJobId;
        this.singerId = singerId;
        this.rankNo = rankNo;
        this.cosineSimilarity = cosineSimilarity;
        this.ecapaScore = ecapaScore;
        this.singerByolScore = singerByolScore;
        this.catalogBatchId = catalogBatchId;
    }
    public Long getSingerId() { return singerId; }
    public short getRankNo() { return rankNo; }
    public BigDecimal getCosineSimilarity() { return cosineSimilarity; }
    public BigDecimal getEcapaScore() { return ecapaScore; }
    public BigDecimal getSingerByolScore() { return singerByolScore; }
}
