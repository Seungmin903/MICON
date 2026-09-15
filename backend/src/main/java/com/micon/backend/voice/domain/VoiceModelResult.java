package com.micon.backend.voice.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "voice_model_result")
public class VoiceModelResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_result_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @Column(name = "component_job_id", nullable = false) private Long componentJobId;
    @Column(name = "model_key", nullable = false, length = 30) private String modelKey;
    @Column(name = "model_version", nullable = false, length = 100) private String modelVersion;
    @Column(nullable = false) private int dimension;
    @Column(name = "vector_json", columnDefinition = "json") private String vectorJson;
    @Column(name = "vector_object_key", length = 1024) private String vectorObjectKey;
    @Column(name = "vector_sha256", nullable = false, length = 64, columnDefinition = "char(64)") private String vectorSha256;
    @Column(name = "segment_info", nullable = false, columnDefinition = "json") private String segmentInfo;
    @Column(name = "quality_metrics", columnDefinition = "json") private String qualityMetrics;
    protected VoiceModelResult() {}
    public VoiceModelResult(VoiceAnalysisSession session, Long componentJobId, String modelKey,
                            String modelVersion, int dimension, String vectorJson, String vectorSha256,
                            String segmentInfo, String qualityMetrics) {
        this.session = session;
        this.componentJobId = componentJobId;
        this.modelKey = modelKey;
        this.modelVersion = modelVersion;
        this.dimension = dimension;
        this.vectorJson = vectorJson;
        this.vectorSha256 = vectorSha256;
        this.segmentInfo = segmentInfo;
        this.qualityMetrics = qualityMetrics;
    }
    public String getModelKey() { return modelKey; }
    public String getModelVersion() { return modelVersion; }
    public int getDimension() { return dimension; }
    public String getVectorJson() { return vectorJson; }
    public String getVectorObjectKey() { return vectorObjectKey; }
    public String getSegmentInfo() { return segmentInfo; }
    public String getQualityMetrics() { return qualityMetrics; }
}
