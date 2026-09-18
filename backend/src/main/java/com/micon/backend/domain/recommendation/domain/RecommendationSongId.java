package com.micon.backend.domain.recommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RecommendationSongId implements Serializable {

    @Column(name = "recommendation_id")
    private Long recommendationId;

    @Column(name = "song_id")
    private String songId;

    protected RecommendationSongId() {
    }

    public RecommendationSongId(Long recommendationId, String songId) {
        this.recommendationId = recommendationId;
        this.songId = songId;
    }

    public Long getRecommendationId() { return recommendationId; }
    public String getSongId() { return songId; }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RecommendationSongId that)) return false;
        return Objects.equals(recommendationId, that.recommendationId) && Objects.equals(songId, that.songId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recommendationId, songId);
    }
}
