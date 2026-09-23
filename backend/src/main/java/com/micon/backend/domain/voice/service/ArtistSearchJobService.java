package com.micon.backend.domain.voice.service;

import com.micon.backend.domain.voice.repository.VoiceAnalysisComponentJobRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisJobRepository;
import com.micon.backend.domain.voice.repository.VoiceModelResultRepository;

import com.micon.backend.domain.voice.client.ArtistSearchJobGateway;
import com.micon.backend.domain.voice.entity.VoiceAnalysisComponentJob;
import com.micon.backend.domain.voice.entity.VoiceAnalysisJob;
import com.micon.backend.domain.voice.entity.VoiceModelResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ArtistSearchJobService {

    private static final String COMPONENT = "ARTIST_SEARCH";

    private final VoiceAnalysisJobRepository analyses;
    private final VoiceAnalysisComponentJobRepository jobs;
    private final VoiceModelResultRepository modelResults;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int topK;
    private final String scoringVersion;
    private final String callbackUrl;
    private final String contractVersion;

    public ArtistSearchJobService(
            VoiceAnalysisJobRepository analyses,
            VoiceAnalysisComponentJobRepository jobs,
            VoiceModelResultRepository modelResults,
            JdbcTemplate jdbc,
            ObjectMapper json,
            @Value("${AI_ARTIST_SEARCH_TOP_K:10}") int topK,
            @Value("${ai.artist-search-scoring-version}") String scoringVersion,
            @Value("${AI_ARTIST_SEARCH_CALLBACK_URL:http://localhost:8080/api/v1/callbacks/voice-analysis-components}") String callbackUrl,
            @Value("${AI_ARTIST_SEARCH_CONTRACT_VERSION:v1}") String contractVersion
    ) {
        this.analyses = analyses;
        this.jobs = jobs;
        this.modelResults = modelResults;
        this.jdbc = jdbc;
        this.json = json;
        this.topK = topK;
        this.scoringVersion = scoringVersion;
        this.callbackUrl = callbackUrl;
        this.contractVersion = contractVersion;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Prepared prepare(long analysisId) {
        VoiceAnalysisJob analysis = analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("voice analysis job was not found"));
        if (!"COMPLETED".equals(analysis.getStatus())) {
            throw new IllegalStateException("artist search requires a completed timbre analysis");
        }
        VoiceAnalysisComponentJob job = jobs.findByAnalysisJobIdAndComponentType(analysisId, COMPONENT)
                .orElse(null);
        if (job == null) {
            job = new VoiceAnalysisComponentJob(analysis, COMPONENT, 1, UUID.randomUUID().toString());
            job.configureArtistSearch(analysis.getSession().getResultVersion(), topK,
                    activeCatalogVersion(), scoringVersion);
            job = jobs.saveAndFlush(job);
        }
        if (!job.canDispatch()) {
            return new Prepared(job.getId(), false, null);
        }

        long sessionId = analysis.getSession().getId();
        analysis.getSession().beginArtistSearch();
        return new Prepared(job.getId(), true, command(analysis, job, sessionId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryPrepared prepareRetry(long userId, long analysisId) {
        VoiceAnalysisJob analysis = analyses.findOwnedForUpdate(analysisId, userId)
                .orElseThrow(() -> new com.micon.backend.common.exception.BusinessException(
                        com.micon.backend.common.exception.ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (!"COMPLETED".equals(analysis.getStatus())) {
            throw new com.micon.backend.common.exception.BusinessException(
                    com.micon.backend.common.exception.ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        VoiceAnalysisComponentJob job = jobs.findByAnalysisJobIdAndComponentType(analysisId, COMPONENT)
                .orElse(null);
        if (job == null) {
            job = new VoiceAnalysisComponentJob(analysis, COMPONENT, 1, UUID.randomUUID().toString());
            job.configureArtistSearch(analysis.getSession().getResultVersion(), topK,
                    activeCatalogVersion(), scoringVersion);
            job = jobs.saveAndFlush(job);
            analysis.getSession().beginArtistSearch();
            return new RetryPrepared(job.getId(), null,
                    command(analysis, job, analysis.getSession().getId()));
        }
        boolean freshDispatch = job.getAiJobId() == null || job.getAiJobId().isBlank();
        try {
            if (freshDispatch) job.retryForFreshDispatch(LocalDateTime.now());
            else job.retry(LocalDateTime.now());
        }
        catch (IllegalStateException exception) {
            throw new com.micon.backend.common.exception.BusinessException(
                    com.micon.backend.common.exception.ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        if (freshDispatch) {
            job.configureArtistSearch(analysis.getSession().getResultVersion(), topK,
                    activeCatalogVersion(), scoringVersion);
        }
        analysis.getSession().beginArtistSearch();
        return new RetryPrepared(job.getId(), job.getAiJobId(),
                freshDispatch
                        ? command(analysis, job, analysis.getSession().getId()) : null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void queued(long jobId, ArtistSearchJobGateway.Result result) {
        VoiceAnalysisComponentJob job = job(jobId);
        job.queued(result.jobId(), LocalDateTime.now());
        job.getSession().beginArtistSearch();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchUnknown(long jobId, String reason) {
        job(jobId).dispatchUnknown(limit(reason), LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long jobId, String code, String reason) {
        VoiceAnalysisComponentJob job = job(jobId);
        LocalDateTime now = LocalDateTime.now();
        job.fail(code, limit(reason), now);
        job.getSession().failArtistSearch(now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failSession(long analysisId) {
        analyses.findById(analysisId).ifPresent(analysis ->
                analysis.getSession().failArtistSearch(LocalDateTime.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disabled(long analysisId) {
        VoiceAnalysisJob analysis = analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("voice analysis job was not found"));
        VoiceAnalysisComponentJob job = jobs.findByAnalysisJobIdAndComponentType(analysisId, COMPONENT)
                .orElseGet(() -> jobs.save(new VoiceAnalysisComponentJob(
                        analysis, COMPONENT, 1, UUID.randomUUID().toString())));
        LocalDateTime now = LocalDateTime.now();
        job.fail("ARTIST_SEARCH_DISABLED", "닮은 가수 검색 기능이 비활성화되어 있습니다.", now);
        analysis.getSession().failArtistSearch(now);
    }

    private ArtistSearchJobGateway.Command command(VoiceAnalysisJob analysis,
            VoiceAnalysisComponentJob job, long sessionId) {
        Map<String, ArtistSearchJobGateway.ModelResult> models = new LinkedHashMap<>();
        models.put("ECAPA", model(sessionId, "ECAPA", 192));
        models.put("SINGER_BYOL", model(sessionId, "SINGER_BYOL", 1000));
        return new ArtistSearchJobGateway.Command(
                job.getRequestId(), analysis.getId(), sessionId, COMPONENT, List.of(COMPONENT),
                job.getAttemptNo(), job.getRequestedResultVersion(), job.getRequestedTopK(), models,
                job.getCatalogVersion(), job.getScoringVersion(), callbackUrl, contractVersion);
    }

    private ArtistSearchJobGateway.ModelResult model(long sessionId, String modelKey, int dimension) {
        VoiceModelResult result = modelResults.findBySessionIdAndModelKey(sessionId, modelKey)
                .orElseThrow(() -> new IllegalStateException(modelKey + " result was not found"));
        if (result.getDimension() != dimension) {
            throw new IllegalStateException(modelKey + " dimension does not match the contract");
        }
        JsonNode vector = json.readTree(result.getVectorJson());
        if (!vector.isArray() || vector.size() != dimension) {
            throw new IllegalStateException(modelKey + " vector does not match the contract");
        }
        List<Double> embedding = new ArrayList<>(dimension);
        vector.forEach(value -> embedding.add(value.asDouble()));
        return new ArtistSearchJobGateway.ModelResult(result.getModelVersion(), dimension, embedding);
    }

    private String activeCatalogVersion() {
        List<String> batches = jdbc.queryForList("""
                SELECT batch_id
                FROM embedding_catalog_active
                WHERE channel = 'default' AND batch_id IS NOT NULL
                """, String.class);
        if (batches.size() != 1) {
            throw new IllegalStateException("one active embedding catalog is required");
        }
        return batches.getFirst();
    }

    private VoiceAnalysisComponentJob job(long jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("artist search job was not found"));
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record Prepared(long jobId, boolean shouldDispatch,
                           ArtistSearchJobGateway.Command command) { }
    public record RetryPrepared(long jobId, String aiJobId,
                                ArtistSearchJobGateway.Command freshCommand) { }
}
