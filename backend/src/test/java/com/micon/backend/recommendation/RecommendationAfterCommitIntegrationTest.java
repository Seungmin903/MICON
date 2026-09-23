package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import com.micon.backend.domain.recommendation.service.RecommendationInput;
import com.micon.backend.domain.recommendation.service.RecommendationInputAssembler;
import com.micon.backend.domain.recommendation.service.RecommendationLifecycleService;
import com.micon.backend.domain.recommendation.service.RecommendationQueryService;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;

import com.micon.backend.domain.recommendation.service.*;
import com.micon.backend.domain.recommendation.client.RecommendationJobGateway;
import com.micon.backend.domain.recommendation.entity.*;
import com.micon.backend.domain.recommendation.repository.*;
import com.micon.backend.domain.user.entity.*;
import com.micon.backend.domain.user.repository.*;
import com.micon.backend.domain.voice.service.VoiceProfileSavedEvent;
import com.micon.backend.domain.voice.entity.*;
import com.micon.backend.domain.voice.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "ai.recommendation.enabled=true",
        "ai.recommendation.recovery.initial-delay=1h"
})
class RecommendationAfterCommitIntegrationTest {
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher events;
    @Autowired UserRepository users;
    @Autowired UserVoiceProfileRepository profiles;
    @Autowired VoiceAnalysisSessionRepository sessions;
    @Autowired RecommendationRepository recommendations;
    @Autowired RecommendationLifecycleService lifecycle;
    @Autowired RecommendationQueryService query;
    @MockitoBean RecommendationInputAssembler inputs;
    @MockitoBean RecommendationJobGateway gateway;

    private RecommendationInput input(UserVoiceProfile profile) {
        return new RecommendationInput(profile.getId(), profile.getSession().getId(), profile.getProfileVersion(), null,
                new RecommendationInput.VoiceProfile(null,null,null,null,null,null,null,null,"{}",null,null,null,null,null),
                List.of(), List.of());
    }
    private long[] saveAndPublish(boolean rollback) {
        return new TransactionTemplate(manager).execute(tx -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID()+"@test.invalid", "not-a-login-password"));
            VoiceAnalysisSession session = sessions.saveAndFlush(new VoiceAnalysisSession(user,null,LocalDateTime.now().plusHours(1)));
            UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user,session,1));
            when(inputs.assemble(user.getId(),profile.getId())).thenReturn(input(profile));
            events.publishEvent(new VoiceProfileSavedEvent(user.getId(),profile.getId(),1));
            assertThat(recommendations.findFirstByUserIdOrderByGeneratedAtDescIdDesc(user.getId())).isEmpty();
            if (rollback) tx.setRollbackOnly();
            return new long[]{user.getId(),profile.getId(),session.getId()};
        });
    }
    @Test void committedProfileCreatesRecommendationAndPersistsAiJobInIndependentTransactions() {
        when(gateway.submit(any())).thenReturn(new RecommendationJobGateway.Result("after-commit-"+UUID.randomUUID(),"queued"));
        long[] saved=saveAndPublish(false);
        var response=query.latest(saved[0]);
        assertThat(response.jobId()).startsWith("after-commit-");
        assertThat(response.status()).isEqualTo(RecommendationStatus.GENERATING);
        assertThat(response.profileId()).isEqualTo(saved[1]);
        assertThat(response.sourceSessionId()).isEqualTo(saved[2]);
        assertThat(response.profileVersion()).isEqualTo(1);
        verify(gateway).submit(any());
    }
    @Test void dispatchFailureIsPersistedWithoutRollingBackSavedProfile() {
        when(gateway.submit(any())).thenThrow(new IllegalStateException("simulated unavailable AI"));
        long[] saved=saveAndPublish(false);
        assertThat(profiles.existsById(saved[1])).isTrue();
        assertThat(query.latest(saved[0]).status()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(query.latest(saved[0]).failureCode()).isEqualTo("AI_DISPATCH_FAILED");
    }
    @Test void rollbackDoesNotDispatch() {
        long[] saved=saveAndPublish(true);
        assertThat(profiles.existsById(saved[1])).isFalse();
        verifyNoInteractions(gateway);
    }
    @Test void remeasurementExcludesOldRecommendationAndDoesNotBlockNewGeneration() {
        when(gateway.submit(any())).thenAnswer(call -> new RecommendationJobGateway.Result("version-"+UUID.randomUUID(),"queued"));
        long[] saved=saveAndPublish(false);
        var old=query.latest(saved[0]);
        RecommendationInput replacement=new TransactionTemplate(manager).execute(tx -> {
            var profile=profiles.findById(saved[1]).orElseThrow();
            var session=sessions.saveAndFlush(new VoiceAnalysisSession(profile.getUser(),null,LocalDateTime.now().plusHours(1)));
            profile.replaceWith(session);
            return input(profile);
        });
        assertThatThrownBy(() -> query.latest(saved[0])).isInstanceOf(com.micon.backend.common.exception.BusinessException.class);
        assertThatThrownBy(() -> query.get(saved[0],old.recommendationId())).isInstanceOf(com.micon.backend.common.exception.BusinessException.class);
        var created=lifecycle.create(saved[0],replacement,RecommendationMode.OVERALL);
        assertThat(created.recommendationId()).isNotEqualTo(old.recommendationId());
        assertThat(query.latest(saved[0]).profileVersion()).isEqualTo(2);
        assertThatThrownBy(() -> lifecycle.create(saved[0],replacement,RecommendationMode.OVERALL))
                .isInstanceOf(com.micon.backend.common.exception.BusinessException.class);
    }

    @Test void recoveryQueryOnlyReturnsProfilesWithoutACurrentRecommendation() {
        long[] saved = new TransactionTemplate(manager).execute(tx -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID()+"@test.invalid", "not-a-login-password"));
            VoiceAnalysisSession session = sessions.saveAndFlush(
                    new VoiceAnalysisSession(user, null, LocalDateTime.now().plusHours(1)));
            UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
            return new long[]{user.getId(), profile.getId()};
        });
        var page = org.springframework.data.domain.PageRequest.of(0, 5);
        assertThat(profiles.findMissingCurrentRecommendationsAfterId(saved[1] - 1, page))
                .extracting(UserVoiceProfile::getId).contains(saved[1]);

        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var profile = profiles.findById(saved[1]).orElseThrow();
            recommendations.saveAndFlush(new Recommendation(profile.getUser(), profile, RecommendationMode.OVERALL));
        });
        assertThat(profiles.findMissingCurrentRecommendationsAfterId(saved[1] - 1, page))
                .extracting(UserVoiceProfile::getId).doesNotContain(saved[1]);

        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var profile = profiles.findById(saved[1]).orElseThrow();
            var session = sessions.saveAndFlush(new VoiceAnalysisSession(
                    profile.getUser(), null, LocalDateTime.now().plusHours(1)));
            profile.replaceWith(session);
        });
        assertThat(profiles.findMissingCurrentRecommendationsAfterId(saved[1] - 1, page))
                .extracting(UserVoiceProfile::getId).contains(saved[1]);
    }

    @Test void fullQueueRetryIsClaimedOnceAndStopsAfterJobAssignment() {
        long recommendationId = new TransactionTemplate(manager).execute(tx -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID()+"@test.invalid", "not-a-login-password"));
            VoiceAnalysisSession session = sessions.saveAndFlush(
                    new VoiceAnalysisSession(user, null, LocalDateTime.now().plusHours(1)));
            UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
            return recommendations.saveAndFlush(
                    new Recommendation(user, profile, RecommendationMode.OVERALL)).getId();
        });
        lifecycle.scheduleDispatchRetry(recommendationId, LocalDateTime.now().minusSeconds(1));
        var now = LocalDateTime.now();
        var page = org.springframework.data.domain.PageRequest.of(0, 100);
        assertThat(recommendations.findDueDispatchRetryIds(RecommendationStatus.GENERATING,
                now, now.minusSeconds(40), page)).contains(recommendationId);

        var claim = lifecycle.claimDispatchRetry(recommendationId, now,
                java.time.Duration.ofSeconds(40), java.time.Duration.ofMinutes(2));
        assertThat(claim).isPresent();
        assertThat(claim.orElseThrow().attempts()).isEqualTo(1);
        assertThat(lifecycle.claimDispatchRetry(recommendationId, now,
                java.time.Duration.ofSeconds(40), java.time.Duration.ofMinutes(2))).isEmpty();

        var afterLease = lifecycle.claimDispatchRetry(recommendationId, now.plusSeconds(41),
                java.time.Duration.ofSeconds(40), java.time.Duration.ofMinutes(2));
        assertThat(afterLease).isPresent();
        assertThat(afterLease.orElseThrow().attempts()).isEqualTo(2);

        lifecycle.assignJob(recommendationId, "queue-retry-" + UUID.randomUUID());
        assertThat(recommendations.findDueDispatchRetryIds(RecommendationStatus.GENERATING,
                now.plusMinutes(1), now.plusSeconds(20), page)).doesNotContain(recommendationId);
    }

    @Test void fullQueueRetryTimesOutInsteadOfGeneratingForever() {
        long recommendationId = new TransactionTemplate(manager).execute(tx -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID()+"@test.invalid", "not-a-login-password"));
            VoiceAnalysisSession session = sessions.saveAndFlush(
                    new VoiceAnalysisSession(user, null, LocalDateTime.now().plusHours(1)));
            UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
            return recommendations.saveAndFlush(
                    new Recommendation(user, profile, RecommendationMode.OVERALL)).getId();
        });
        lifecycle.scheduleDispatchRetry(recommendationId, LocalDateTime.now().minusSeconds(1));
        var now = LocalDateTime.now();
        assertThat(lifecycle.claimDispatchRetry(recommendationId, now,
                java.time.Duration.ofSeconds(40), java.time.Duration.ofSeconds(30))).isPresent();
        // A second BE instance must not fail the first instance's in-flight lease.
        assertThat(lifecycle.claimDispatchRetry(recommendationId, now.plusSeconds(31),
                java.time.Duration.ofSeconds(40), java.time.Duration.ofSeconds(30))).isEmpty();
        assertThat(recommendations.findById(recommendationId).orElseThrow().getStatus())
                .isEqualTo(RecommendationStatus.GENERATING);

        assertThat(lifecycle.claimDispatchRetry(recommendationId, now.plusSeconds(41),
                java.time.Duration.ofSeconds(40), java.time.Duration.ofSeconds(30))).isEmpty();
        var recommendation = recommendations.findById(recommendationId).orElseThrow();
        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(recommendation.getFailureCode()).isEqualTo("AI_CAPACITY_TIMEOUT");
    }

    @Test void ambiguousInitialDispatchIsNotResubmittedAfterAWorkerRestart() {
        long recommendationId = new TransactionTemplate(manager).execute(tx -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID()+"@test.invalid", "not-a-login-password"));
            VoiceAnalysisSession session = sessions.saveAndFlush(
                    new VoiceAnalysisSession(user, null, LocalDateTime.now().plusHours(1)));
            UserVoiceProfile profile = profiles.saveAndFlush(new UserVoiceProfile(user, session, 1));
            return recommendations.saveAndFlush(
                    new Recommendation(user, profile, RecommendationMode.OVERALL)).getId();
        });
        var afterLease = LocalDateTime.now().plusSeconds(41);

        assertThat(lifecycle.claimDispatchRetry(recommendationId, afterLease,
                java.time.Duration.ofSeconds(40), java.time.Duration.ofMinutes(2))).isEmpty();
        assertThat(recommendations.findById(recommendationId).orElseThrow().getFailureCode())
                .isEqualTo("AI_DISPATCH_UNCONFIRMED");
    }
}
