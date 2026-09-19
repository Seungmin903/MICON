package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.entity.Recommendation;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.recommendation.entity.RecommendationShelf;
import com.micon.backend.domain.recommendation.entity.RecommendationSong;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import com.micon.backend.domain.recommendation.repository.RecommendationShelfRepository;
import com.micon.backend.domain.recommendation.repository.RecommendationSongRepository;
import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;

import com.micon.backend.domain.recommendation.service.RecommendationResultStore;
import com.micon.backend.domain.recommendation.client.RecommendationResultGateway;
import com.micon.backend.domain.recommendation.entity.*;
import com.micon.backend.domain.recommendation.repository.*;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RecommendationResultStoreIntegrationTest {
    @Autowired RecommendationResultStore store;
    @Autowired RecommendationRepository recommendations;
    @Autowired
    RecommendationShelfRepository shelves;
    @Autowired RecommendationSongRepository songs;
    @Autowired UserRepository users;
    @Autowired
    VoiceAnalysisSessionRepository sessions;
    @Autowired
    UserVoiceProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entityManager;

    @Test
    void atomicallyStoresCompletedShelvesAndStringSongs() throws Exception {
        Recommendation recommendation = recommendation("result-complete@micon.com", "job-complete");
        String firstSong = insertCatalogSong("첫 곡");
        String secondSong = insertCatalogSong("둘째 곡");
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
        String songId = insertCatalogSong("중복 곡");
        RecommendationResultGateway.Item item = item(1, songId, null, null, null);

        RecommendationResultStore.Outcome outcome = store.apply("job-invalid", new RecommendationResultGateway.Job(
                "job-invalid", RecommendationResultGateway.Status.COMPLETE, 100, "done",
                new RecommendationResultGateway.Result(
                        new RecommendationResultGateway.Algorithm("score-v2", "shelves-v1"), List.of(
                                new RecommendationResultGateway.Shelf("voice", "음색", null, List.of(item)),
                                new RecommendationResultGateway.Shelf("range", "음역", null, List.of(item)))), null));

        entityManager.flush();
        assertThat(outcome).isEqualTo(RecommendationResultStore.Outcome.FAILED);
        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(recommendation.getFailureCode()).isEqualTo("AI_RESULT_INVALID");
        assertThat(recommendation.getFailureReason()).contains("duplicate songId");
        assertThat(shelves.findByRecommendationIdOrderByOrder(recommendation.getId())).isEmpty();
        assertThat(songs.findByRecommendationIdOrderByOverallRank(recommendation.getId())).isEmpty();
    }

    @Test
    void storesRecommendedKeysAtAvailableInstrumentalRangeBoundaries() throws Exception {
        Recommendation recommendation = recommendation("result-boundaries@micon.com", "job-boundaries");
        String lowSong = insertCatalogSong("최저 키 곡");
        String highSong = insertCatalogSong("최고 키 곡");

        RecommendationResultStore.Outcome outcome = store.apply("job-boundaries",
                new RecommendationResultGateway.Job(
                        "job-boundaries", RecommendationResultGateway.Status.COMPLETE, 100, "done",
                        new RecommendationResultGateway.Result(
                                new RecommendationResultGateway.Algorithm("score-v2", "shelves-v1"),
                                List.of(new RecommendationResultGateway.Shelf("range", "내 음역", "음역 적합도",
                                        List.of(
                                                item(1, lowSong, json.readTree("{\"keyShift\":-6}"),
                                                        (short) -6, null),
                                                item(2, highSong, json.readTree("{\"keyShift\":5}"),
                                                        (short) 5, null))))), null));

        entityManager.flush();
        entityManager.clear();
        Recommendation saved = recommendations.findById(recommendation.getId()).orElseThrow();
        assertThat(outcome).isEqualTo(RecommendationResultStore.Outcome.COMPLETED);
        assertThat(saved.getStatus()).isEqualTo(RecommendationStatus.COMPLETED);
        assertThat(songs.findByRecommendationIdOrderByOverallRank(saved.getId()))
                .extracting(RecommendationSong::getRecommendedKey)
                .containsExactly((short) -6, (short) 5);
    }

    @Test
    void rejectsRecommendedKeyOutsideAvailableInstrumentalRange() throws Exception {
        assertInvalidRangeResult("key-plus-six", json.readTree("{\"score\":0.91,\"keyShift\":6}"), (short) 6);
    }

    @Test
    void rejectsRangeFitKeyShiftThatDiffersFromRecommendedKey() throws Exception {
        assertInvalidRangeResult("key-mismatch", json.readTree("{\"score\":0.91,\"keyShift\":-1}"), (short) -2);
    }

    @Test
    void rejectsNonIntegralRangeFitKeyShift() throws Exception {
        assertInvalidRangeResult("non-integral-key", json.readTree("{\"score\":0.91,\"keyShift\":-2.0}"),
                (short) -2);
    }

    @Test
    void rejectsRangeFitWithoutRecommendedKey() throws Exception {
        assertInvalidRangeResult("missing-key", json.readTree("{\"score\":0.91,\"keyShift\":-2}"), null);
    }

    @Test
    void rejectsRecommendedKeyWithoutRangeFit() {
        assertInvalidRangeResult("missing-range-fit", null, (short) -2);
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

    private RecommendationResultGateway.Item item(int position, String songId,
                                                   tools.jackson.databind.JsonNode rangeFit,
                                                   tools.jackson.databind.JsonNode metrics) {
        return item(position, songId, rangeFit, (short) -2, metrics);
    }

    private RecommendationResultGateway.Item item(int position, String songId,
                                                   tools.jackson.databind.JsonNode rangeFit,
                                                   Short recommendedKey,
                                                   tools.jackson.databind.JsonNode metrics) {
        return new RecommendationResultGateway.Item(position, songId, "CONSENSUS", "두 축 모두 가까움",
                new BigDecimal("0.81"), rangeFit, recommendedKey, new BigDecimal("0.88"), metrics,
                "score-v2", "shelves-v1");
    }

    private void assertInvalidRangeResult(String suffix, tools.jackson.databind.JsonNode rangeFit,
                                          Short recommendedKey) {
        Recommendation recommendation = recommendation("result-" + suffix + "@micon.com", "job-" + suffix);
        String songId = insertCatalogSong("잘못된 음역 결과 " + suffix);
        RecommendationResultStore.Outcome outcome = store.apply(recommendation.getJobId(),
                new RecommendationResultGateway.Job(
                        recommendation.getJobId(), RecommendationResultGateway.Status.COMPLETE, 100, "done",
                        new RecommendationResultGateway.Result(
                                new RecommendationResultGateway.Algorithm("score-v2", "shelves-v1"),
                                List.of(new RecommendationResultGateway.Shelf("range", "내 음역", "음역 적합도",
                                        List.of(item(1, songId, rangeFit, recommendedKey, null))))), null));

        entityManager.flush();
        assertThat(outcome).isEqualTo(RecommendationResultStore.Outcome.FAILED);
        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(recommendation.getFailureCode()).isEqualTo("AI_RESULT_INVALID");
        assertThat(shelves.findByRecommendationIdOrderByOrder(recommendation.getId())).isEmpty();
        assertThat(songs.findByRecommendationIdOrderByOverallRank(recommendation.getId())).isEmpty();
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

    private String insertCatalogSong(String title) {
        String songId = "result-" + UUID.randomUUID();
        jdbc.update("INSERT INTO song (song_id, title, status, practice_ready) VALUES (?, ?, 'ACTIVE', TRUE)",
                songId, title);
        return songId;
    }
}
