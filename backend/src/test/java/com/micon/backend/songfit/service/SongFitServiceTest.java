package com.micon.backend.songfit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.micon.backend.domain.songfit.dto.SongFitResponse;
import com.micon.backend.domain.songfit.dto.SongFitResponse.BurdenSection;
import com.micon.backend.domain.songfit.client.SongFitGateway;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository.RecommendationRow;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository.SongRangeRow;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository.UserRangeRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.micon.backend.domain.songfit.service.SongFitEvaluationFailedException;
import com.micon.backend.domain.songfit.service.SongFitService;
import com.micon.backend.domain.songfit.service.SongNotFoundException;
import com.micon.backend.domain.songfit.service.VoiceAnalysisNotFoundException;
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
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.of(
			new RecommendationRow(-2, decimal("0.88"), decimal("0.90"), decimal("0.77"), null,
				"음역이 잘 맞습니다.", "fit-v1", "ECAPA", "목소리 물성이 가까운 가수")
		));
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of(
			new BurdenSection(40L, "CHORUS", "후렴", 60_000, 90_000, "HIGH_NOTE", decimal("82"), "고음 구간")
		));

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 5);

		assertThat(response.evaluationStatus()).isEqualTo("AVAILABLE");
		assertThat(response.originalKeyFit().score()).isEqualByComparingTo("85.00");
		assertThat(response.selectedKeyFit().score()).isEqualByComparingTo("100.00");
		assertThat(response.recommendedKeyOffset()).isEqualTo(-2);
		assertThat(response.recommendationScores().overall()).isEqualByComparingTo("0.88");
		assertThat(response.recommendationScores().range()).isEqualByComparingTo("0.90");
		assertThat(response.recommendationScores().timbre()).isEqualByComparingTo("0.77");
		assertThat(response.recommendationScores().rhythm()).isNull();
		assertThat(response.recommendationScores().timbreAxis()).isEqualTo("ECAPA");
		assertThat(response.recommendationScores().timbreLabel()).isEqualTo("목소리 물성이 가까운 가수");
		assertThat(response.burdenSections()).singleElement()
			.extracting(BurdenSection::sectionName)
			.isEqualTo("후렴");
	}

	@Test
	void returnsZeroWhenOriginalKeyIsRecommended() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
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
			new UserRangeRow("SAVED", "INSUFFICIENT_DATA", null, null)
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
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.empty());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.evaluationStatus()).isEqualTo("UNAVAILABLE");
		assertThat(response.unavailableReason()).isEqualTo("SONG_RANGE_UNAVAILABLE");
		assertThat(response.selectedKeyFit()).isNull();
	}

	@Test
	void calculatesPlayableRecommendedKeyWhenRecommendationHasNotBeenGenerated() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("50"), decimal("70"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.evaluationStatus()).isEqualTo("AVAILABLE");
		assertThat(response.originalKeyFit()).isNotNull();
		assertThat(response.recommendedKeyOffset()).isEqualTo(3);
		assertThat(response.reason()).contains("세 키 올리기");
		assertThat(response.recommendationScores()).isNull();
	}

	@Test
	void recommendsTheFirstKeyThatReachesNinetyEightPercentCoverage() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("60"), decimal("64"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("56"), decimal("64"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());

		SongFitResponse response = service.getSongFit(7L, "10", 20L, 0);

		assertThat(response.recommendedKeyOffset()).isEqualTo(2);
		assertThat(response.reason()).contains("두 키 올리기");
	}

	@Test
	void capsARecommendationThatNeedsFourOrMoreKeysAtThree() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("66"), decimal("67"))
		));
		when(repository.findLatestSongRange("10")).thenReturn(Optional.of(
			new SongRangeRow(30L, decimal("60"), decimal("61"))
		));
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.empty());
		when(repository.findBurdenSections("10", 30L)).thenReturn(List.of());

		assertThat(service.getSongFit(7L, "10", 20L, 0).recommendedKeyOffset()).isEqualTo(3);
	}

	@Test
	void delegatesSongFitEvaluationToAiWhenEnabled() {
		service = new SongFitService(repository, gateway, true);
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
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
			"10", 20L, 0, "AVAILABLE", null, null, 3, null, "AI 계산", List.of(), null
		);
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(aiResponse);

		assertThat(service.getSongFit(7L, "10", 20L, 0)).isSameAs(aiResponse);
	}

	@Test
	void rejectsAiRecommendedKeyAbovePlayableRange() {
		service = new SongFitService(repository, gateway, true);
		stubAvailableSongFitInputs();
		SongFitResponse aiResponse = new SongFitResponse(
			"10", 20L, 0, "AVAILABLE", null, null, 4, null, "AI 계산", List.of(), null
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
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
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

	@Test
	void rejectsAiResponseForDifferentKey() {
		service = new SongFitService(repository, gateway, true);
		stubAvailableSongFitInputs();
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(new SongFitResponse(
			"10", 20L, -2, "AVAILABLE", null, null, -2, null, "잘못된 키", List.of(), null
		));
		assertThatThrownBy(() -> service.getSongFit(7L, "10", 20L, 0))
			.isInstanceOf(SongFitEvaluationFailedException.class);
	}

	@Test
	void forwardsOptionalTimbreMetadataToAiWithoutChangingScores() {
		service = new SongFitService(repository, gateway, true);
		stubAvailableSongFitInputs();
		when(repository.findRecommendation(7L, 20L, "10")).thenReturn(Optional.of(
			new RecommendationRow(-2, decimal("0.88"), decimal("0.90"), decimal("0.77"), null,
				"음역", "fit-v1", "ECAPA", "음색 설명")
		));
		when(gateway.evaluate(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
			SongFitGateway.Command request = invocation.getArgument(0);
			assertThat(request.recommendationScores().timbreAxis()).isEqualTo("ECAPA");
			assertThat(request.recommendationScores().timbreLabel()).isEqualTo("음색 설명");
			assertThat(request.recommendationScores().timbre()).isEqualByComparingTo("0.77");
			return new SongFitResponse("10", 20L, 0, "AVAILABLE", null, null, -2,
				request.recommendationScores(), "음역", List.of(), null);
		});
		assertThat(service.getSongFit(7L, "10", 20L, 0).recommendationScores().timbreLabel()).isEqualTo("음색 설명");
	}

	private void stubAvailableSongFitInputs() {
		when(repository.songExists("10")).thenReturn(true);
		when(repository.findUserRange(7L, 20L)).thenReturn(Optional.of(
			new UserRangeRow("SAVED", "AVAILABLE", decimal("55"), decimal("75"))
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
