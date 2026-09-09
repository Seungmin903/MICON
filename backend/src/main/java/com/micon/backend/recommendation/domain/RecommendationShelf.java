package com.micon.backend.recommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "recommendation_shelf")
public class RecommendationShelf {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_shelf_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private Recommendation recommendation;

    @Column(name = "shelf_key", nullable = false, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 50)
    private String metric;

    @Column(name = "shelf_order", nullable = false)
    private short order;

    protected RecommendationShelf() {
    }

    public RecommendationShelf(Recommendation recommendation, String key, String title, int order, String metric) {
        if (recommendation == null) {
            throw new IllegalArgumentException("recommendation is required");
        }
        if (key == null || key.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("key and title must not be blank");
        }
        if (order < 1 || order > Short.MAX_VALUE) {
            throw new IllegalArgumentException("order must be positive");
        }
        this.recommendation = recommendation;
        this.key = key;
        this.title = title;
        this.order = (short) order;
        this.metric = metric;
    }

    public Long getId() { return id; }
    public Recommendation getRecommendation() { return recommendation; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getMetric() { return metric; }
    public short getOrder() { return order; }
}
