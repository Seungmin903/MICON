package com.micon.backend.songfit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.micon.backend.songfit.api.SongFitResponse;
import com.micon.backend.songfit.api.SongFitResponse.BurdenSection;
import com.micon.backend.songfit.client.SongFitGateway;
import com.micon.backend.songfit.repository.SongFitQueryRepository;
import com.micon.backend.songfit.repository.SongFitQueryRepository.RecommendationRow;
import com.micon.backend.songfit.repository.SongFitQueryRepository.SongRangeRow;
import com.micon.backend.songfit.repository.SongFitQueryRepository.UserRangeRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SongFitServiceTest {

	@Mock
	private SongFitQueryRepository repository;
	@Mock
	private SongFitGateway gateway;

	private SongFitService service;

	@BeforeEach
	void setUp() {
		service = new SongFitService(repository, gateway, false);
	}

	@Test
	void comparesOriginalAndSelectedKeyAgainstStableRange() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.of(
			new RecommendationRow(-2, decimal("0.88"), decimal("0.90"), decimal("0.77"), null,
				"음역이 잘 맞습니다.", "fit-v1")
		));
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of(
			new BurdenSection(40L, "CHORUS", "후렴", 60_000, 90_000, "HIGH_NOTE", decimal("82"), "고음 구간")
		));

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 5);

		assertThat(response.evaluationStatus()).isEqualTo("AVAILABLE");
		assertThat(response.originalKeyFit().score()).isEqualByComparingTo("75.00");
		assertThat(response.selectedKeyFit().score()).isEqualByComparingTo("100.00");
		assertThat(response.recommendedKeyOffset()).isEqualTo(-2);
		assertThat(response.recommendationScores().overall()).isEqualByComparingTo("0.88");
		assertThat(response.recommendationScores().range()).isEqualByComparingTo("0.90");
		assertThat(response.recommendationScores().timbre()).isEqualByComparingTo("0.77");
		assertThat(response.recommendationScores().rhythm()).isNull();
		assertThat(response.burdenSections()).singleElement()
			.extracting(BurdenSection::sectionName)
			.isEqualTo("후렴");
	}

	@Test
	void returnsZeroWhenOriginalKeyIsRecommended() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("55"), decimal("75"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.of(
			new RecommendationRow(0, decimal("0.91"), decimal("1.0"), decimal("0.88"), null,
				"원키가 적합합니다.", "fit-v1")
		));
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.recommendedKeyOffset()).isZero();
	}

	@Test
	void returnsUnavailableWhenStableRangeIsMissing() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("INSUFFICIENT_DATA", null, null)
		));

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.evaluationStatus()).isEqualTo("UNAVAILABLE");
		assertThat(response.unavailableReason()).isEqualTo("USER_STABLE_RANGE_UNAVAILABLE");
		assertThat(response.originalKeyFit()).isNull();
	}

	@Test
	void hidesAnotherUsersVoiceAnalysis() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getSongFit(7L, "10", 20L, 0))
			.isInstanceOf(VoiceAnalysisNotFoundException.class);
	}

	@Test
	void rejectsMissingSongBeforeReadingAnalysisData() {
		when(repository.songExists("10")).thenReturn(false);

		assertThatThrownBy(() -> service.getSongFit(7L, "10", 20L, 0))
			.isInstanceOf(SongNotFoundException.class);
	}

	@Test
	void returnsUnavailableWhenCompletedSongRangeIsMissing() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.empty());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.evaluationStatus()).isEqualTo("UNAVAILABLE");
		assertThat(response.unavailableReason()).isEqualTo("SONG_RANGE_UNAVAILABLE");
		assertThat(response.selectedKeyFit()).isNull();
	}

	@Test
	void stillReturnsRangeComparisonWhenRecommendationHasNotBeenGenerated() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.evaluationStatus()).isEqualTo("AVAILABLE");
		assertThat(response.originalKeyFit()).isNotNull();
		assertThat(response.recommendedKeyOffset()).isNull();
		assertThat(response.recommendationScores()).isNull();
	}

	@Test
	void delegatesSongFitEvaluationToAiWhenEnabled() {
		service = new SongFitService(repository, gateway, true);
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());
		SongFitResponse aiResponse = new SongFitResponse(
			"10", 20L, -1, "AVAILABLE", null, null, -2, null, "AI 계산", List.of(), null
		);
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(aiResponse);

		assertThat(service.getSongFit(7L, "10", 20L, -1)).isSameAs(aiResponse);
	}

	@Test
	void acceptsMaximumPlayableAiRecommendedKey() {
		service = new SongFitService(repository, gateway, true);
		stubAvailableSongFitInputs();
		SongFitResponse aiResponse = new SongFitResponse(
			"10", 20L, 0, "AVAILABLE", null, null, 5, null, "AI 계산", List.of(), null
		);
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(aiResponse);

		assertThat(service.getSongFit(7L, "10", 20L, 0)).isSameAs(aiResponse);
	}

	@Test
	void rejectsAiRecommendedKeyAbovePlayableRange() {
		service = new SongFitService(repository, gateway, true);
		stubAvailableSongFitInputs();
		SongFitResponse aiResponse = new SongFitResponse(
			"10", 20L, 0, "AVAILABLE", null, null, 6, null, "AI 계산", List.of(), null
		);
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(aiResponse);

		assertThatThrownBy(() -> service.getSongFit(7L, "10", 20L, 0))
			.isInstanceOf(SongFitEvaluationFailedException.class);
	}

	@Test
	void distinguishesAiEvaluationFailure() {
		service = new SongFitService(repository, gateway, true);
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("AI down"));

		assertThatThrownBy(() -> service.getSongFit(7L, "10", 20L, 0))
			.isInstanceOf(SongFitEvaluationFailedException.class);
	}

	private void stubAvailableSongFitInputs() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());
	}

	private static BigDecimal decimal(String value) {
		return new BigDecimal(value);
	}
}
