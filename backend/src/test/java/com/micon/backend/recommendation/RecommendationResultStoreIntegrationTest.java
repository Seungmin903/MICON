package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.RecommendationResultStore;
import com.micon.backend.recommendation.client.RecommendationResultGateway;
import com.micon.backend.recommendation.domain.*;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RecommendationResultStoreIntegrationTest {
    @Autowired RecommendationResultStore store;
    @Autowired RecommendationRepository recommendations;
    @Autowired RecommendationShelfRepository shelves;
    @Autowired RecommendationSongRepository songs;
    @Autowired UserRepository users;
    @Autowired VoiceAnalysisSessionRepository sessions;
    @Autowired UserVoiceProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entityManager;

    @Test
    void atomicallyStoresCompletedShelvesAndNumericSongs() throws Exception {
        Recommendation recommendation = recommendation("result-complete@micon.com", "job-complete");
        long firstSong = insertCatalogSong("첫 곡");
        long secondSong = insertCatalogSong("둘째 곡");
        var rangeFit = json.readTree("{\"score\":0.91,\"keyShift\":-2}");
        var metrics = json.readTree("{\"coverage\":0.95}");

        RecommendationResultStore.Outcome outcome = store.apply("job-complete", new RecommendationResultGateway.Job(
                "job-complete", RecommendationResultGateway.Status.COMPLETE, 100, "done",
                new RecommendationResultGateway.Result(
                        new RecommendationResultGateway.Algorithm("score-v2", "shelves-v1"),
                        List.of(
                                new RecommendationResultGateway.Shelf("voice", "내 목소리", "음색 유사도", List.of(
                                        item(1, firstSong, rangeFit, metrics))),
                                new RecommendationResultGateway.Shelf("range", "내 음역", "음역 적합도", List.of(
                                        item(1, secondSong, rangeFit, metrics))))), null));

        entityManager.flush();
        entityManager.clear();
        Recommendation saved = recommendations.findById(recommendation.getId()).orElseThrow();
        assertThat(outcome).isEqualTo(RecommendationResultStore.Outcome.COMPLETED);
        assertThat(saved.getStatus()).isEqualTo(RecommendationStatus.COMPLETED);
        assertThat(saved.getProgress()).isEqualTo((short) 100);
        assertThat(saved.getScoringVersion()).isEqualTo("score-v2");
        assertThat(shelves.findByRecommendationIdOrderByOrder(saved.getId()))
                .extracting(RecommendationShelf::getKey).containsExactly("voice", "range");
        assertThat(songs.findByRecommendationIdOrderByOverallRank(saved.getId()))
                .extracting(song -> song.getId().getSongId()).containsExactly(firstSong, secondSong);
    }

    @Test
    void marksInvalidDuplicateSongResultAsFailedWithoutPartialRows() {
        Recommendation recommendation = recommendation("result-invalid@micon.com", "job-invalid");
        long songId = insertCatalogSong("중복 곡");
        RecommendationResultGateway.Item item = item(1, songId, null, null);

        RecommendationResultStore.Outcome outcome = store.apply("job-invalid", new RecommendationResultGateway.Job(
                "job-invalid", RecommendationResultGateway.Status.COMPLETE, 100, "done",
                new RecommendationResultGateway.Result(null, List.of(
                        new RecommendationResultGateway.Shelf("voice", "음색", null, List.of(item)),
                        new RecommendationResultGateway.Shelf("range", "음역", null, List.of(item)))), null));

        entityManager.flush();
        assertThat(outcome).isEqualTo(RecommendationResultStore.Outcome.FAILED);
        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(recommendation.getFailureCode()).isEqualTo("AI_RESULT_INVALID");
        assertThat(shelves.findByRecommendationIdOrderByOrder(recommendation.getId())).isEmpty();
        assertThat(songs.findByRecommendationIdOrderByOverallRank(recommendation.getId())).isEmpty();
    }

    @Test
    void doesNotRegressProgressWhenAnOlderPollArrives() {
        Recommendation recommendation = recommendation("result-progress@micon.com", "job-progress");
        store.apply("job-progress", new RecommendationResultGateway.Job(
                "job-progress", RecommendationResultGateway.Status.PROCESSING, 70, "working", null, null));
        store.apply("job-progress", new RecommendationResultGateway.Job(
                "job-progress", RecommendationResultGateway.Status.PROCESSING, 40, "older", null, null));

        assertThat(recommendation.getProgress()).isEqualTo((short) 70);
    }

    private RecommendationResultGateway.Item item(int position, long songId,
                                                   tools.jackson.databind.JsonNode rangeFit,
                                                   tools.jackson.databind.JsonNode metrics) {
        return new RecommendationResultGateway.Item(position, songId, "CONSENSUS", "두 축 모두 가까움",
                new BigDecimal("0.81"), rangeFit, (short) -2, new BigDecimal("0.88"), metrics,
                "score-v2", "shelves-v1");
    }

    private Recommendation recommendation(String email, String jobId) {
        User user = users.saveAndFlush(new User(email, "encoded-password"));
        VoiceAnalysisSession session = sessions.saveAndFlush(
                new VoiceAnalysisSession(user, null, java.time.LocalDateTime.now().plusHours(1)));
        UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
        Recommendation recommendation = new Recommendation(user, profile, RecommendationMode.OVERALL);
        recommendation.assignJob(jobId);
        return recommendations.saveAndFlush(recommendation);
    }

    private long insertCatalogSong(String title) {
        jdbc.update("INSERT INTO song (title, status, practice_ready) VALUES (?, 'ACTIVE', TRUE)", title);
        return jdbc.queryForObject("SELECT MAX(song_id) FROM song", Long.class);
    }
}
