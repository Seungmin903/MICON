package com.micon.backend.domain.recommendation.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.micon.backend.domain.recommendation.client.RecommendationResultGateway;
import com.micon.backend.domain.recommendation.domain.Recommendation;
import com.micon.backend.domain.recommendation.domain.RecommendationRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationShelf;
import com.micon.backend.domain.recommendation.domain.RecommendationShelfRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationSong;
import com.micon.backend.domain.recommendation.domain.RecommendationSongRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationStatus;
import com.micon.backend.domain.song.repository.SongQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class RecommendationResultStore {
    private static final int MAX_ITEMS_PER_SHELF = 50;
    private static final short MIN_RECOMMENDED_KEY = -6;
    private static final short MAX_RECOMMENDED_KEY = 5;

    private final RecommendationRepository recommendations;
    private final RecommendationShelfRepository shelves;
    private final RecommendationSongRepository songs;
    private final SongQueryRepository songCatalog;
    private final ObjectMapper objectMapper;

    public RecommendationResultStore(RecommendationRepository recommendations,
                                     RecommendationShelfRepository shelves,
                                     RecommendationSongRepository songs,
                                     SongQueryRepository songCatalog,
                                     ObjectMapper objectMapper) {
        this.recommendations = recommendations;
        this.shelves = shelves;
        this.songs = songs;
        this.songCatalog = songCatalog;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Outcome apply(String expectedJobId, RecommendationResultGateway.Job job) {
        Recommendation recommendation = recommendations.findByJobIdForUpdate(expectedJobId)
                .orElseThrow(() -> new IllegalStateException("recommendation job not found: " + expectedJobId));
        if (recommendation.getStatus() != RecommendationStatus.GENERATING) {
            return Outcome.ALREADY_TERMINAL;
        }
        if (!recommendation.isForCurrentProfile()) {
            return fail(recommendation, "PROFILE_SUPERSEDED", "A newer voice profile replaced this recommendation input");
        }
        try {
            validateJob(expectedJobId, job);
            return switch (job.status()) {
                case QUEUED, PROCESSING -> updateProgress(recommendation, job.progress());
                case FAILED -> fail(recommendation, "AI_RECOMMENDATION_FAILED", firstNonBlank(job.error(), job.message()));
                case COMPLETE -> complete(recommendation, job.result());
            };
        } catch (InvalidRecommendationResultException exception) {
            return fail(recommendation, "AI_RESULT_INVALID", exception.getMessage());
        }
    }

    @Transactional
    public Outcome failPending(String jobId, String code, String reason) {
        Recommendation recommendation = recommendations.findByJobIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("recommendation job not found"));
        if (recommendation.getStatus() != RecommendationStatus.GENERATING) return Outcome.ALREADY_TERMINAL;
        return fail(recommendation, code, reason);
    }

    private Outcome updateProgress(Recommendation recommendation, int progress) {
        recommendation.updateProgress(progress);
        return Outcome.PROCESSING;
    }

    private Outcome complete(Recommendation recommendation, RecommendationResultGateway.Result result) {
        if (result == null || result.shelves() == null) {
            throw invalid("complete response has no result.shelves");
        }
        ValidatedResult validated = validateResult(result);
        LocalDateTime now = LocalDateTime.now();
        String algorithmVersion = result.algorithm() == null ? null : result.algorithm().shelvesVersion();
        String scoringVersion = result.algorithm() == null ? null : result.algorithm().scoringVersion();
        String compositionVersion = firstCompositionVersion(validated.items(), algorithmVersion);
        if (validated.items().isEmpty()) {
            recommendation.completeEmpty(algorithmVersion, scoringVersion, compositionVersion, now, null);
            return Outcome.EMPTY;
        }

        Set<String> existingIds = songCatalog.findExistingIds(validated.songIds());
        if (!existingIds.equals(validated.songIds())) {
            Set<String> missing = new HashSet<>(validated.songIds());
            missing.removeAll(existingIds);
            throw invalid("unknown songId: " + missing);
        }

        songs.deleteByRecommendationId(recommendation.getId());
        shelves.deleteByRecommendationId(recommendation.getId());
        shelves.flush();

        List<RecommendationSong> storedSongs = new ArrayList<>();
        int overallRank = 1;
        for (ValidatedShelf validatedShelf : validated.shelves()) {
            RecommendationShelf shelf = shelves.saveAndFlush(new RecommendationShelf(
                    recommendation, validatedShelf.source().key(), validatedShelf.source().title(),
                    validatedShelf.order(), validatedShelf.source().metric()));
            for (RecommendationResultGateway.Item item : validatedShelf.items()) {
                RecommendationSong song = new RecommendationSong(
                        recommendation, shelf, item.songId(), overallRank++, item.position());
                song.applyResult(item.axis(), item.label(), item.recommendedKey(), item.finalScore(),
                        item.timbreScore(), json(item.rangeFit()), json(item.metrics()), null,
                        item.scoringVersion(), item.compositionVersion());
                storedSongs.add(song);
            }
        }
        songs.saveAll(storedSongs);
        recommendation.complete(algorithmVersion, scoringVersion, compositionVersion, now, null);
        return Outcome.COMPLETED;
    }

    private ValidatedResult validateResult(RecommendationResultGateway.Result result) {
        Set<String> shelfKeys = new HashSet<>();
        Set<String> songIds = new HashSet<>();
        List<ValidatedShelf> validatedShelves = new ArrayList<>();
        List<RecommendationResultGateway.Item> allItems = new ArrayList<>();
        int order = 1;
        validateAlgorithm(result.algorithm());
        for (RecommendationResultGateway.Shelf shelf : result.shelves()) {
            if (shelf == null || blank(shelf.key()) || blank(shelf.title())) {
                throw invalid("shelf key and title are required");
            }
            if (shelf.key().length() > 50 || shelf.title().length() > 100
                    || (shelf.metric() != null && shelf.metric().length() > 255)) {
                throw invalid("shelf text exceeds storage limit: " + shelf.key());
            }
            if (!shelfKeys.add(shelf.key())) {
                throw invalid("duplicate shelf key: " + shelf.key());
            }
            List<RecommendationResultGateway.Item> items = shelf.items() == null ? List.of() : shelf.items();
            if (items.size() > MAX_ITEMS_PER_SHELF) {
                throw invalid("shelf contains more than 50 songs: " + shelf.key());
            }
            Set<Integer> positions = new HashSet<>();
            for (RecommendationResultGateway.Item item : items) {
                validateItem(item, shelf.key());
                if (!positions.add(item.position())) {
                    throw invalid("duplicate position in shelf " + shelf.key() + ": " + item.position());
                }
                if (!songIds.add(item.songId())) {
                    throw invalid("duplicate songId across shelves: " + item.songId());
                }
            }
            List<RecommendationResultGateway.Item> sorted = items.stream()
                    .sorted(java.util.Comparator.comparingInt(RecommendationResultGateway.Item::position)).toList();
            validatedShelves.add(new ValidatedShelf(shelf, order++, sorted));
            allItems.addAll(sorted);
        }
        if (allItems.size() > Short.MAX_VALUE) {
            throw invalid("recommendation contains too many songs");
        }
        return new ValidatedResult(validatedShelves, allItems, songIds);
    }

    private void validateAlgorithm(RecommendationResultGateway.Algorithm algorithm) {
        if (algorithm == null || blank(algorithm.scoringVersion()) || blank(algorithm.shelvesVersion())) {
            throw invalid("algorithm versions are required");
        }
        if (algorithm.scoringVersion().length() > 50 || algorithm.shelvesVersion().length() > 50) {
            throw invalid("algorithm version exceeds storage limit");
        }
    }

    private void validateItem(RecommendationResultGateway.Item item, String shelfKey) {
        if (item == null || item.songId() == null || item.songId().isBlank() || item.songId().length() > 64) {
            throw invalid("non-blank string songId of at most 64 characters is required in shelf: " + shelfKey);
        }
        if (item.position() < 1 || item.position() > MAX_ITEMS_PER_SHELF) {
            throw invalid("position must be between 1 and 50 in shelf: " + shelfKey);
        }
        if ((item.axis() != null && item.axis().length() > 50)
                || (item.label() != null && item.label().length() > 100)
                || (item.scoringVersion() != null && item.scoringVersion().length() > 50)
                || (item.compositionVersion() != null && item.compositionVersion().length() > 50)) {
            throw invalid("item text exceeds storage limit for songId: " + item.songId());
        }
        validateRangeFit(item);
    }

    private void validateRangeFit(RecommendationResultGateway.Item item) {
        boolean hasRangeFit = item.rangeFit() != null && !item.rangeFit().isNull();
        boolean hasRecommendedKey = item.recommendedKey() != null;
        if (hasRangeFit != hasRecommendedKey) {
            throw invalid("rangeFit and recommendedKey must both be null or both be present for songId: "
                    + item.songId());
        }
        if (!hasRangeFit) return;
        if (item.recommendedKey() < MIN_RECOMMENDED_KEY || item.recommendedKey() > MAX_RECOMMENDED_KEY) {
            throw invalid("recommendedKey must be between -6 and 5 for songId: " + item.songId());
        }
        JsonNode keyShift = item.rangeFit().get("keyShift");
        if (keyShift == null || !keyShift.isIntegralNumber()
                || !keyShift.bigIntegerValue().equals(BigInteger.valueOf(item.recommendedKey()))) {
            throw invalid("rangeFit.keyShift must be an integer matching recommendedKey for songId: "
                    + item.songId());
        }
    }

    private void validateJob(String expectedJobId, RecommendationResultGateway.Job job) {
        if (job == null || !Objects.equals(expectedJobId, job.id())) {
            throw invalid("AI job id does not match requested job");
        }
        if (job.status() == null || job.progress() < 0 || job.progress() > 100) {
            throw invalid("AI job status or progress is invalid");
        }
    }

    private Outcome fail(Recommendation recommendation, String code, String reason) {
        recommendation.fail(code, abbreviate(firstNonBlank(reason, "AI recommendation failed")), LocalDateTime.now());
        return Outcome.FAILED;
    }

    private String json(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw invalid("result JSON cannot be stored");
        }
    }

    private String firstCompositionVersion(List<RecommendationResultGateway.Item> items, String fallback) {
        return items.stream().map(RecommendationResultGateway.Item::compositionVersion)
                .filter(value -> !blank(value)).findFirst().orElse(fallback);
    }

    private String firstNonBlank(String first, String second) { return blank(first) ? second : first; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String abbreviate(String value) { return value.length() <= 1000 ? value : value.substring(0, 1000); }
    private InvalidRecommendationResultException invalid(String message) {
        return new InvalidRecommendationResultException(message);
    }

    public enum Outcome { PROCESSING, COMPLETED, EMPTY, FAILED, ALREADY_TERMINAL }
    private record ValidatedShelf(RecommendationResultGateway.Shelf source, int order,
                                  List<RecommendationResultGateway.Item> items) {}
    private record ValidatedResult(List<ValidatedShelf> shelves,
                                   List<RecommendationResultGateway.Item> items, Set<String> songIds) {}
    private static final class InvalidRecommendationResultException extends RuntimeException {
        private InvalidRecommendationResultException(String message) { super(message); }
    }
}
