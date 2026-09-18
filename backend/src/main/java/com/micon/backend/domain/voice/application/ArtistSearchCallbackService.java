package com.micon.backend.domain.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.preference.repository.PreferenceCatalogRepository;
import com.micon.backend.domain.voice.api.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.domain.voice.domain.SimilarArtistResult;
import com.micon.backend.domain.voice.domain.SimilarArtistResultRepository;
import com.micon.backend.domain.voice.domain.VoiceAnalysisComponentJob;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ArtistSearchCallbackService {

    private final SimilarArtistResultRepository results;
    private final PreferenceCatalogRepository artists;

    public ArtistSearchCallbackService(SimilarArtistResultRepository results,
                                       PreferenceCatalogRepository artists) {
        this.results = results;
        this.artists = artists;
    }

    public void accept(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        validateCommon(job, request);
        switch (request.status()) {
            case RUNNING -> {
                job.running(request.processedAt());
                job.getSession().beginArtistSearch();
            }
            case COMPLETED -> complete(job, request);
            case FAILED -> fail(job, request);
            case INSUFFICIENT_DATA -> throw invalid("닮은 가수 검색은 INSUFFICIENT_DATA 상태를 사용하지 않습니다.");
        }
    }

    private void complete(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        List<VoiceAnalysisComponentCallbackRequest.Artist> ranked = request.artists();
        if (ranked == null || job.getRequestedTopK() == null
                || ranked.size() > job.getRequestedTopK()) {
            throw invalid("완료 콜백의 artists 수가 요청한 topK를 초과했습니다.");
        }
        Set<Long> artistIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (var artist : ranked) {
            if (artist.artistId() == null || artist.artistId() < 1
                    || artist.rank() == null || artist.rank() < 1 || artist.rank() > 10
                    || !artistIds.add(artist.artistId()) || !ranks.add(artist.rank())
                    || invalidScore(artist.similarity())
                    || invalidOptionalScore(artist.ecapaScore())
                    || invalidOptionalScore(artist.singerByolScore())) {
                throw invalid("유사 가수 ID·순위·점수가 올바르지 않습니다.");
            }
        }
        if (!ranks.equals(expectedRanks(ranked.size()))
                || artists.findArtistsByIds(List.copyOf(artistIds)).size() != artistIds.size()) {
            throw invalid("유사 가수는 존재하는 artistId와 1부터 연속된 순위를 사용해야 합니다.");
        }

        results.deleteBySessionId(job.getSession().getId());
        results.saveAll(ranked.stream().map(artist -> new SimilarArtistResult(
                job.getSession(), job.getId(), artist.artistId(), artist.rank().shortValue(),
                decimal(artist.similarity()), decimal(artist.ecapaScore()),
                decimal(artist.singerByolScore()), request.catalogVersion())).toList());
        job.complete("ARTIST_SEARCH", request.modelVersion(), request.processedAt());
        job.getSession().completeArtistSearch(ranked.isEmpty(), request.processedAt());
    }

    private void fail(VoiceAnalysisComponentJob job, VoiceAnalysisComponentCallbackRequest request) {
        if (request.failureCode() == null || request.failureCode().isBlank()
                || request.failureReason() == null || request.failureReason().isBlank()) {
            throw invalid("실패 콜백에는 failureCode와 failureReason이 필요합니다.");
        }
        LocalDateTime at = request.processedAt();
        job.fail(request.failureCode(), request.failureReason(), at);
        job.getSession().failArtistSearch(at);
    }

    private void validateCommon(VoiceAnalysisComponentJob job,
                                VoiceAnalysisComponentCallbackRequest request) {
        if (!"ARTIST_SEARCH".equals(request.component())
                || !"ARTIST_SEARCH".equals(request.modelKey())
                || request.modelVersion() == null || request.modelVersion().isBlank()
                || !request.modelVersion().equals(job.getScoringVersion())
                || request.resultVersion() == null
                || !request.resultVersion().equals(job.getRequestedResultVersion())
                || request.catalogVersion() == null || request.catalogVersion().isBlank()
                || !request.catalogVersion().equals(job.getCatalogVersion())) {
            throw invalid("검색 결과 버전 또는 활성 임베딩 카탈로그가 요청과 일치하지 않습니다.");
        }
    }

    private Set<Integer> expectedRanks(int count) {
        Set<Integer> expected = new HashSet<>();
        for (int rank = 1; rank <= count; rank++) expected.add(rank);
        return expected;
    }

    private boolean invalidScore(Double value) {
        return value == null || !Double.isFinite(value) || value < -1.0 || value > 1.0;
    }

    private boolean invalidOptionalScore(Double value) {
        return value != null && (!Double.isFinite(value) || value < -1.0 || value > 1.0);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK, message);
    }
}
