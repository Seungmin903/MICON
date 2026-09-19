package com.micon.backend.domain.recommendation.service;

import java.util.List;

public interface RecommendationPreferenceReader {
    Preferences findByUserId(long userId);

    record Preferences(List<String> genres, List<String> artists) {
        public Preferences {
            genres = genres == null ? List.of() : List.copyOf(genres);
            artists = artists == null ? List.of() : List.copyOf(artists);
        }

        public static Preferences empty() {
            return new Preferences(List.of(), List.of());
        }
    }
}
