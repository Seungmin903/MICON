package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.*;
import com.micon.backend.recommendation.client.RecommendationJobGateway;
import com.micon.backend.recommendation.domain.*;
import com.micon.backend.user.domain.*;
import com.micon.backend.voice.application.VoiceProfileSavedEvent;
import com.micon.backend.voice.domain.*;
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

@SpringBootTest(properties = "ai.recommendation.enabled=true")
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
}
