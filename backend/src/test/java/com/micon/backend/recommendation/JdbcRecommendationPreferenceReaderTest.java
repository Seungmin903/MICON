package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.RecommendationPreferenceReader;
import com.micon.backend.recommendation.infrastructure.JdbcRecommendationPreferenceReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.generate-unique-name=true"
})
@Import(JdbcRecommendationPreferenceReader.class)
class JdbcRecommendationPreferenceReaderTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JdbcRecommendationPreferenceReader reader;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS genre (genre_id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS singer (singer_id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_genre_preference (user_id BIGINT, genre_id BIGINT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_singer_preference (user_id BIGINT, singer_id BIGINT)");
        jdbcTemplate.update("DELETE FROM user_genre_preference");
        jdbcTemplate.update("DELETE FROM user_singer_preference");
        jdbcTemplate.update("DELETE FROM genre");
        jdbcTemplate.update("DELETE FROM singer");
    }

    @Test
    void returnsSavedNamesInStableOrder() {
        jdbcTemplate.update("INSERT INTO genre VALUES (1, '록'), (2, '발라드')");
        jdbcTemplate.update("INSERT INTO singer VALUES (1, '태연'), (2, '아이유')");
        jdbcTemplate.update("INSERT INTO user_genre_preference VALUES (7, 1), (7, 2)");
        jdbcTemplate.update("INSERT INTO user_singer_preference VALUES (7, 1), (7, 2)");

        RecommendationPreferenceReader.Preferences preferences = reader.findByUserId(7L);

        assertThat(preferences.genres()).containsExactly("록", "발라드");
        assertThat(preferences.artists()).containsExactly("아이유", "태연");
    }

    @Test
    void returnsEmptyListsWhenUserHasNoPreferences() {
        RecommendationPreferenceReader.Preferences preferences = reader.findByUserId(8L);

        assertThat(preferences.genres()).isEmpty();
        assertThat(preferences.artists()).isEmpty();
    }
}
