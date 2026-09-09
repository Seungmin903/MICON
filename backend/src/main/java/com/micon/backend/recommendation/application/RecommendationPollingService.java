package com.micon.backend.recommendation.application;

import com.micon.backend.recommendation.client.RecommendationResultGateway;
import com.micon.backend.recommendation.config.RecommendationPollingProperties;
import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationPollingService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationPollingService.class);

    private final RecommendationRepository recommendations;
    private final RecommendationResultGateway gateway;
    private final RecommendationResultStore resultStore;
    private final RecommendationPollingProperties properties;

    public RecommendationPollingService(RecommendationRepository recommendations,
                                        RecommendationResultGateway gateway,
                                        RecommendationResultStore resultStore,
                                        RecommendationPollingProperties properties) {
        this.recommendations = recommendations;
        this.gateway = gateway;
        this.resultStore = resultStore;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ai.recommendation.polling.interval:1500ms}",
            initialDelayString = "${ai.recommendation.polling.initial-delay:5s}")
    public void scheduledPoll() {
        if (properties.enabled()) {
            pollGeneratingRecommendations();
        }
    }

    public PollSummary pollGeneratingRecommendations() {
        List<Recommendation> pending = recommendations
                .findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING);
        Map<RecommendationResultStore.Outcome, Integer> counts = new EnumMap<>(RecommendationResultStore.Outcome.class);
        int errors = 0;
        for (Recommendation recommendation : pending) {
            try {
                RecommendationResultStore.Outcome outcome = resultStore.apply(
                        recommendation.getJobId(), gateway.fetch(recommendation.getJobId()));
                counts.merge(outcome, 1, Integer::sum);
            } catch (RuntimeException exception) {
                errors++;
                log.warn("Recommendation polling failed temporarily. recommendationId={}, jobId={}",
                        recommendation.getId(), recommendation.getJobId(), exception);
            }
        }
        PollSummary summary = new PollSummary(pending.size(), counts, errors);
        if (!pending.isEmpty()) {
            log.info("Recommendation polling completed. requested={}, outcomes={}, errors={}",
                    summary.requested(), summary.outcomes(), summary.errors());
        }
        return summary;
    }

    public record PollSummary(int requested, Map<RecommendationResultStore.Outcome, Integer> outcomes, int errors) {
        public PollSummary {
            outcomes = Map.copyOf(outcomes);
        }
    }
}
