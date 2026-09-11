package com.micon.backend.recommendation;

import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.recommendation.domain.RecommendationShelf;
import com.micon.backend.recommendation.domain.RecommendationShelfRepository;
import com.micon.backend.recommendation.domain.RecommendationSong;
import com.micon.backend.recommendation.domain.RecommendationSongRepository;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RecommendationPersistenceIntegrationTests {

    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired VoiceAnalysisRepository analyses;
    @Autowired RecommendationRepository recommendations;
    @Autowired RecommendationShelfRepository shelves;
    @Autowired RecommendationSongRepository songs;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void persistsRecommendationShelvesAndSongsInTheirDefinedOrder() {
        Fixture fixture = fixture("recommendation-order@micon.com");
        Recommendation recommendation = fixture.recommendation();
        RecommendationShelf range = shelves.saveAndFlush(
                new RecommendationShelf(recommendation, "range", "내 음역에 잘 맞는 곡", 1, "rangeFit"));
        RecommendationShelf timbre = shelves.saveAndFlush(
                new RecommendationShelf(recommendation, "timbre", "내 음색과 닮은 곡", 2, "timbreScore"));

        insertCatalogSong(101L);
        insertCatalogSong(102L);
        songs.save(new RecommendationSong(recommendation, timbre, 102L, 2, 1));
        songs.save(new RecommendationSong(recommendation, range, 101L, 1, 1));
        songs.flush();

        assertThat(shelves.findByRecommendationIdOrderByOrder(recommendation.getId()))
                .extracting(RecommendationShelf::getKey)
                .containsExactly("range", "timbre");
        assertThat(songs.findByRecommendationIdOrderByOverallRank(recommendation.getId()))
                .extracting(song -> song.getId().getSongId())
                .containsExactly(101L, 102L);
    }

    @Test
    void databaseAllowsFiftySongsPerShelf() {
        Fixture fixture = fixture("recommendation-limit@micon.com");
        Recommendation recommendation = fixture.recommendation();
        RecommendationShelf shelf = shelves.saveAndFlush(
                new RecommendationShelf(recommendation, "overall", "종합 추천", 1, "finalScore"));

        for (int rank = 1; rank <= 50; rank++) {
            long songId = 1000L + rank;
            insertCatalogSong(songId);
            insertSong(recommendation.getId(), shelf.getId(), songId, rank, rank);
        }
        RecommendationShelf secondShelf = shelves.saveAndFlush(
                new RecommendationShelf(recommendation, "range", "음역 추천", 2, "rangeFit"));
        insertCatalogSong(1051L);
        insertSong(recommendation.getId(), secondShelf.getId(), 1051L, 51, 1);

        assertThat(countSongs(recommendation.getId())).isEqualTo(51);
    }

    @Test
    void databaseRejectsDuplicateSongRankAndShelfOrderWithinARecommendation() {
        Fixture fixture = fixture("recommendation-unique@micon.com");
        Recommendation recommendation = fixture.recommendation();
        RecommendationShelf shelf = shelves.saveAndFlush(
                new RecommendationShelf(recommendation, "overall", "종합 추천", 1, "finalScore"));
        insertCatalogSong(2001L);
        insertCatalogSong(2002L);
        insertSong(recommendation.getId(), shelf.getId(), 2001L, 1, 1);

        assertThatThrownBy(() -> insertSong(recommendation.getId(), shelf.getId(), 2001L, 2, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSong(recommendation.getId(), shelf.getId(), 2002L, 1, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO recommendation_shelf "
                        + "(recommendation_id, shelf_key, title, shelf_order) VALUES (?, ?, ?, ?)",
                recommendation.getId(), "another", "다른 섹션", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordsJobProgressAndTerminalState() {
        Recommendation recommendation = fixture("recommendation-state@micon.com").recommendation();
        LocalDateTime completedAt = LocalDateTime.now();

        recommendation.assignJob("recommend-job-1");
        recommendation.updateProgress(70);
        recommendation.complete("recommend-v1", "score-v2", "composition-v3", completedAt, completedAt.plusDays(7));
        recommendations.flush();

        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.COMPLETED);
        assertThat(recommendation.getProgress()).isEqualTo((short) 100);
        assertThat(recommendation.getJobId()).isEqualTo("recommend-job-1");
        assertThat(recommendation.getCompletedAt()).isEqualTo(completedAt);
        assertThatThrownBy(() -> recommendation.updateProgress(80))
                .isInstanceOf(IllegalStateException.class);
    }

    private Fixture fixture(String email) {
        User user = users.saveAndFlush(new User(email, "encoded-password"));
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, "recommendations/input.wav", "audio/wav", false));
        VoiceAnalysis analysis = analyses.saveAndFlush(
                new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        Recommendation recommendation = recommendations.saveAndFlush(
                new Recommendation(user, analysis, RecommendationMode.OVERALL));
        return new Fixture(recommendation);
    }

    private void insertSong(long recommendationId, long shelfId, long songId, int overallRank, int shelfPosition) {
        jdbcTemplate.update("""
                INSERT INTO recommendation_song (
                    recommendation_id, song_id, recommendation_shelf_id, overall_rank, shelf_position
                ) VALUES (?, ?, ?, ?, ?)
                """, recommendationId, songId, shelfId, overallRank, shelfPosition);
    }

    private void insertCatalogSong(long songId) {
        jdbcTemplate.update(
                "INSERT INTO song (song_id, title, status, practice_ready) VALUES (?, ?, 'ACTIVE', TRUE)",
                songId, "test-song-" + songId);
    }

    private int countSongs(long recommendationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_song WHERE recommendation_id = ?",
                Integer.class, recommendationId);
        return count == null ? 0 : count;
    }

    private record Fixture(Recommendation recommendation) {
    }
}
