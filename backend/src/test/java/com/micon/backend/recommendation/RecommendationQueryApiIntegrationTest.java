package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.RecommendationResultStore;
import com.micon.backend.recommendation.client.RecommendationResultGateway;
import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.UserVoiceProfile;
import com.micon.backend.voice.domain.UserVoiceProfileRepository;
import com.micon.backend.voice.domain.VoiceAnalysisSession;
import com.micon.backend.voice.domain.VoiceAnalysisSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecommendationQueryApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired RecommendationResultStore resultStore;
    @Autowired RecommendationRepository recommendations;
    @Autowired UserRepository users;
    @Autowired VoiceAnalysisSessionRepository sessions;
    @Autowired UserVoiceProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void returnsOwnedRecommendationInShelfAndPositionOrder() throws Exception {
        User user = user("query-owner@micon.com");
        Recommendation recommendation = recommendation(user, "query-job");
        long songOne = song("첫 곡");
        long songTwo = song("둘째 곡");
        complete(recommendation, songOne, songTwo);

        mockMvc.perform(get("/recommendations/{id}", recommendation.getId()).with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationId").value(recommendation.getId()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.shelves[0].key").value("voice"))
                .andExpect(jsonPath("$.data.shelves[0].items[0].songId").value(songOne))
                .andExpect(jsonPath("$.data.shelves[0].items[1].songId").value(songTwo))
                .andExpect(jsonPath("$.data.shelves[0].items[0].rangeFit.score").value(0.91));
    }

    @Test
    void returnsLatestRecommendationAndSongDetail() throws Exception {
        User user = user("query-latest@micon.com");
        recommendation(user, "older-job");
        Recommendation latest = recommendation(user, "latest-job");
        long songId = song("상세 곡");
        complete(latest, songId);

        mockMvc.perform(get("/recommendations/latest").with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationId").value(latest.getId()));
        mockMvc.perform(get("/recommendations/{id}/songs/{songId}", latest.getId(), songId).with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.songId").value(songId))
                .andExpect(jsonPath("$.data.position").value(1))
                .andExpect(jsonPath("$.data.finalScore").value(0.88));
    }

    @Test
    void hidesAnotherUsersRecommendationAndRejectsUnauthenticatedRequest() throws Exception {
        User owner = user("query-hidden-owner@micon.com");
        User other = user("query-hidden-other@micon.com");
        Recommendation recommendation = recommendation(owner, "hidden-job");

        mockMvc.perform(get("/recommendations/{id}", recommendation.getId()).with(auth(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"));
        mockMvc.perform(get("/recommendations/{id}", recommendation.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()).claim("sid", "test-session"));
    }

    private User user(String email) {
        return users.saveAndFlush(new User(email, "encoded-password"));
    }

    private Recommendation recommendation(User user, String jobId) {
        UserVoiceProfile profile = profiles.findByUserId(user.getId()).orElseGet(() -> {
            VoiceAnalysisSession session = sessions.saveAndFlush(
                    new VoiceAnalysisSession(user, null, LocalDateTime.now().plusHours(1)));
            return profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
        });
        Recommendation recommendation = new Recommendation(user, profile, RecommendationMode.OVERALL);
        recommendation.assignJob(jobId);
        return recommendations.saveAndFlush(recommendation);
    }

    private long song(String title) {
        jdbc.update("INSERT INTO song (title, status, practice_ready) VALUES (?, 'ACTIVE', TRUE)", title);
        return jdbc.queryForObject("SELECT MAX(song_id) FROM song", Long.class);
    }

    private void complete(Recommendation recommendation, long... songIds) throws Exception {
        var rangeFit = json.readTree("{\"score\":0.91}");
        List<RecommendationResultGateway.Item> items = java.util.stream.IntStream.range(0, songIds.length)
                .mapToObj(index -> new RecommendationResultGateway.Item(
                        index + 1, songIds[index], "CONSENSUS", "추천 사유", new BigDecimal("0.80"),
                        rangeFit, (short) -1, new BigDecimal("0.88"), null, "score-v2", "shelves-v1"))
                .toList();
        resultStore.apply(recommendation.getJobId(), new RecommendationResultGateway.Job(
                recommendation.getJobId(), RecommendationResultGateway.Status.COMPLETE, 100, "done",
                new RecommendationResultGateway.Result(
                        new RecommendationResultGateway.Algorithm("score-v2", "shelves-v1"),
                        List.of(new RecommendationResultGateway.Shelf(
                                "voice", "내 목소리와 닮은 곡", "음색 유사도", items))), null));
    }
}
