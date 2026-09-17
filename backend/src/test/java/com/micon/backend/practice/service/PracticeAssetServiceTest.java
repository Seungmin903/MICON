package com.micon.backend.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.catalogmedia.CatalogMediaProperties;
import com.micon.backend.practice.repository.PracticeAssetRepository;
import com.micon.backend.practice.repository.PracticeAssetRepository.MediaRow;
import com.micon.backend.practice.repository.PracticeAssetRepository.NoteRow;
import com.micon.backend.practice.repository.PracticeAssetRepository.SongAssetRow;
import com.micon.backend.voice.storage.VoiceStorage;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PracticeAssetServiceTest {

	@Mock
	private PracticeAssetRepository repository;
	@Mock
	private VoiceStorage storage;

	private PracticeAssetService service;

	@BeforeEach
	void setUp() {
		service = new PracticeAssetService(
			repository,
			new ObjectMapper(),
			storage,
			new CatalogMediaProperties("catalog/test/", Duration.ofMinutes(10)),
			Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC)
		);
		org.mockito.Mockito.lenient().when(storage.createDownloadUrl(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any())).thenReturn(URI.create("https://storage.test/instrumental"));
	}

	@Test
	void returnsOnlyReadyAssetsForSelectedKey() {
		when(repository.findSong("10")).thenReturn(Optional.of(
			new SongAssetRow("10", "연습곡", true, "가사", "\"[00:00.00] 가사\"", 20L, "analysis-v1",
				"G minor", new BigDecimal("134.0"), "4/4", "{\"beatTimes\":[0.45],\"offsetBeats\":1.0}")
		));
		when(repository.findInstrumental("10", -2)).thenReturn(Optional.of(
			new MediaRow(30L, "/songs/10/-2.mp3", "audio/mpeg", 1234L, "hash")
		));
		when(repository.findNotes(20L)).thenReturn(Optional.of(
			new NoteRow("[{\"startMs\":0,\"midi\":60}]", "notes-v2")
		));
		when(repository.findSections("10", 20L)).thenReturn(List.of(
			new com.micon.backend.practice.api.PracticeAssetResponse.Section(
				40L, "CHORUS", "후렴", 1000, 2000, 1, "HIGH_NOTE",
				new BigDecimal("80.00"), new BigDecimal("0.95"), "고음 구간"
			)
		));

		var response = service.get("10", -2);

		assertThat(response.selectedKey()).isEqualTo(-2);
		assertThat(response.instrumental().mediaId()).isEqualTo(30L);
		assertThat(response.instrumental().playbackUrl()).isEqualTo("https://storage.test/instrumental");
		assertThat(response.instrumental().playbackUrlExpiresAt()).isEqualTo(Instant.parse("2026-09-10T12:10:00Z"));
		assertThat(response.lyricsSynced()).isEqualTo("[00:00.00] 가사");
		assertThat(response.targetNotes().get(0).get("midi").asInt()).isEqualTo(60);
		assertThat(response.keyName()).isEqualTo("G minor");
		assertThat(response.tempoBpm()).isEqualByComparingTo("134.0");
		assertThat(response.timeSignature()).isEqualTo("4/4");
		assertThat(response.beatGrid().get("beatTimes").get(0).asDouble()).isEqualTo(0.45);
		assertThat(response.dataVersion()).isEqualTo("notes-v2");
		assertThat(response.sections()).singleElement()
			.extracting(com.micon.backend.practice.api.PracticeAssetResponse.Section::sectionName)
			.isEqualTo("후렴");
	}

	@Test
	void rejectsUnsupportedTransposedKey() {
		when(repository.findSong("10")).thenReturn(Optional.of(
			new SongAssetRow("10", "연습곡", true, "가사", "[]", 20L, "v1", null, null, null, null)
		));
		when(repository.findInstrumental("10", 3)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get("10", 3))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PRACTICE_KEY));
	}

	@Test
	void returnsMrBySongIdWithoutRequiringAnalysisAssets() {
		when(repository.findSong("10")).thenReturn(Optional.of(
			new SongAssetRow("10", "연습곡", false, null, "\"[00:00.00] 가사\"", null, null,
				null, null, null, null)
		));
		when(repository.findInstrumental("10", 0)).thenReturn(Optional.of(
			new MediaRow(30L, "/songs/10/original.mp3", "audio/mpeg", 1234L, "hash")
		));

		var response = service.getMr("10", 0);

		assertThat(response.songId()).isEqualTo("10");
		assertThat(response.selectedKey()).isZero();
		assertThat(response.instrumental().mediaId()).isEqualTo(30L);
		assertThat(response.instrumental().playbackUrl()).isEqualTo("https://storage.test/instrumental");
		assertThat(response.instrumental().playbackUrlExpiresAt())
			.isEqualTo(Instant.parse("2026-09-10T12:10:00Z"));
		assertThat(response.lyricsSynced()).isEqualTo("[00:00.00] 가사");
	}

	@Test
	void rejectsSongWithoutCompletedPracticeData() {
		when(repository.findSong("10")).thenReturn(Optional.of(
			new SongAssetRow("10", "연습곡", false, null, null, null, null, null, null, null, null)
		));

		assertThatThrownBy(() -> service.get("10", 0))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ASSET_NOT_READY));
	}

	@Test
	void guideContainsOnlyStoredDifficultySections() {
		when(repository.findSong("10")).thenReturn(Optional.of(
			new SongAssetRow("10", "연습곡", true, "가사", "[]", 20L, "v1", null, null, null, null)
		));
		when(repository.findInstrumental("10", 0)).thenReturn(Optional.of(
			new MediaRow(30L, "/songs/10/original.mp3", "audio/mpeg", null, null)
		));
		when(repository.findNotes(20L)).thenReturn(Optional.of(new NoteRow("[]", "v1")));
		when(repository.findSections("10", 20L)).thenReturn(List.of(
			new com.micon.backend.practice.api.PracticeAssetResponse.Section(
				40L, "VERSE", "1절", 0, 1000, 1, "NONE", null, null, null
			),
			new com.micon.backend.practice.api.PracticeAssetResponse.Section(
				41L, "CHORUS", "후렴", 1000, 2000, 2, "HIGH_NOTE",
				new BigDecimal("82"), new BigDecimal("0.95"), "고음 구간"
			)
		));

		var guide = service.getGuide("10", 0);

		assertThat(guide.difficultySections()).singleElement()
			.extracting(com.micon.backend.practice.api.PracticeAssetResponse.Section::sectionId)
			.isEqualTo(41L);
	}
}
