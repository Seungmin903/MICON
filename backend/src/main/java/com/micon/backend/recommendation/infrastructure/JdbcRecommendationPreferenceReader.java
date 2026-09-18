package com.micon.backend.domain.recommendation.infrastructure;

import com.micon.backend.domain.recommendation.application.RecommendationPreferenceReader;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcRecommendationPreferenceReader implements RecommendationPreferenceReader {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcRecommendationPreferenceReader(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Preferences findByUserId(long userId) {
        Map<String, Object> parameters = Map.of("userId", userId);
        List<String> genres = jdbcTemplate.queryForList("""
                SELECT COALESCE(genre.preference_label, genre.name)
                FROM user_genre_preference preference
                JOIN genre ON genre.genre_id = preference.genre_id
                WHERE preference.user_id = :userId
                ORDER BY genre.name, genre.genre_id
                """, parameters, String.class);
        List<String> artists = jdbcTemplate.queryForList("""
                SELECT singer.name
                FROM user_singer_preference preference
                JOIN singer ON singer.singer_id = preference.singer_id
                WHERE preference.user_id = :userId
                ORDER BY singer.name, singer.singer_id
                """, parameters, String.class);
        return new Preferences(genres, artists);
    }
}
