package com.micon.backend.domain.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.common.observability.AccessLog;
import com.micon.backend.domain.practice.api.PracticeAssetResponse;
import com.micon.backend.domain.practice.repository.PracticeAssetRepository;
import com.micon.backend.catalogmedia.CatalogMediaProperties;
import com.micon.backend.voice.storage.VoiceStorage;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 연습 시작 전에 필요한 반주·가사·목표 음정·구간·예상 난관을 선택 키에 맞춰 조회한다. */
@Service
public class PracticeAssetService {

	private final PracticeAssetRepository repository;
	private final ObjectMapper objectMapper;
	private final VoiceStorage storage;
	private final CatalogMediaProperties mediaProperties;
	private final Clock clock;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeAssetService 객체를 생성한다.
	public PracticeAssetService(
		PracticeAssetRepository repository,
		ObjectMapper objectMapper,
		VoiceStorage storage,
		CatalogMediaProperties mediaProperties,
		Clock clock
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.storage = storage;
		this.mediaProperties = mediaProperties;
		this.clock = clock;
	}

	// 기능: get 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PracticeAssetResponse.Detail get(String songId, int selectedKey) {
		PracticeAssetRepository.SongAssetRow song = repository.findSong(songId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SONG_NOT_FOUND));
		if (!song.practiceReady() || song.analysisId() == null) {
			AccessLog.event("practice_asset_blocked", true, "song_id", songId, "selected_key", selectedKey,
				"reason", "song_not_ready", "practice_ready", song.practiceReady(),
				"analysis_present", song.analysisId() != null);
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}

		PracticeAssetRepository.MediaRow media = repository.findInstrumental(songId, selectedKey)
			.orElseThrow(() -> blocked(songId, selectedKey, "instrumental_missing", selectedKey == 0
				? ErrorCode.PRACTICE_ASSET_NOT_READY : ErrorCode.UNSUPPORTED_PRACTICE_KEY));
		PracticeAssetRepository.NoteRow note = repository.findNotes(song.analysisId())
			.orElseThrow(() -> blocked(songId, selectedKey, "notes_missing", ErrorCode.PRACTICE_ASSET_NOT_READY));
		if (song.lyricsSynced() == null || song.lyricsSynced().isBlank()) {
			throw blocked(songId, selectedKey, "synced_lyrics_missing", ErrorCode.PRACTICE_ASSET_NOT_READY);
		}
		return new PracticeAssetResponse.Detail(
			song.songId(),
			song.title(),
			selectedKey,
			toMedia(media),
			song.lyricsPlain(),
			readLrc(song.lyricsSynced()),
			readJson(note.karaokeNotes()),
			note.karaokeVersion() == null ? song.analysisVersion() : note.karaokeVersion(),
			repository.findSections(songId, song.analysisId()),
			song.keyName(),
			song.tempoBpm(),
			song.timeSignature(),
			readNullableJson(song.beatGrid())
		);
	}

	// 기능: 곡 ID와 선택 키에 해당하는 MR을 조회하고 재생 URL을 발급한다.
	public PracticeAssetResponse.Instrumental getMr(String songId, int selectedKey) {
		PracticeAssetRepository.SongAssetRow song = repository.findSong(songId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SONG_NOT_FOUND));
		PracticeAssetRepository.MediaRow media = findInstrumental(song.songId(), selectedKey);
		return new PracticeAssetResponse.Instrumental(
			song.songId(),
			selectedKey,
			toMedia(media),
			readLrc(song.lyricsSynced())
		);
	}

	// 기능: getGuide 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PracticeAssetResponse.Guide getGuide(String songId, int selectedKey) {
		PracticeAssetResponse.Detail assets = get(songId, selectedKey);
		return new PracticeAssetResponse.Guide(
			assets.songId(),
			assets.selectedKey(),
			assets.dataVersion(),
			assets.sections().stream()
				.filter(section -> !"NONE".equals(section.difficultyType()))
				.toList()
		);
	}

	// 기능: 선택 키에 맞는 MR을 찾고 준비되지 않은 경우 원인에 맞는 오류를 반환한다.
	private PracticeAssetRepository.MediaRow findInstrumental(String songId, int selectedKey) {
		return repository.findInstrumental(songId, selectedKey)
			.orElseThrow(() -> new BusinessException(selectedKey == 0
				? ErrorCode.PRACTICE_ASSET_NOT_READY
				: ErrorCode.UNSUPPORTED_PRACTICE_KEY));
	}

	// 기능: 저장된 MR 메타데이터에 만료 시간이 있는 재생 URL을 결합한다.
	private PracticeAssetResponse.Media toMedia(PracticeAssetRepository.MediaRow media) {
		String playbackUrl = storage.createDownloadUrl(media.filePath(), mediaProperties.downloadUrlTtl()).toString();
		Instant playbackUrlExpiresAt = clock.instant().plus(mediaProperties.downloadUrlTtl());
		return new PracticeAssetResponse.Media(
			media.mediaId(), media.filePath(), media.mimeType(), media.fileBytes(), media.sha256(),
			playbackUrl, playbackUrlExpiresAt
		);
	}

	private BusinessException blocked(String songId, int selectedKey, String reason, ErrorCode errorCode) {
		AccessLog.event("practice_asset_blocked", true, "song_id", songId, "selected_key", selectedKey,
			"reason", reason);
		return new BusinessException(errorCode);
	}

	// 기능: readJson 비즈니스 로직을 수행한다.
	private JsonNode readJson(String value) {
		try {
			return objectMapper.readTree(value);
		} catch (Exception exception) {
			AccessLog.event("practice_asset_blocked", true, "reason", "invalid_asset_json");
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}
	}

	// 기능: readNullableJson 비즈니스 로직을 수행한다.
	private JsonNode readNullableJson(String value) {
		return value == null || value.isBlank() ? null : readJson(value);
	}

	// 기능: readLrc 비즈니스 로직을 수행한다.
	private String readLrc(String value) {
		try {
			JsonNode node = objectMapper.readTree(value);
			return node != null && node.isString() ? node.asText() : value;
		} catch (Exception ignored) {
			return value;
		}
	}
}
