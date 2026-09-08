package com.micon.backend.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.practice.api.PracticeAssetResponse;
import com.micon.backend.practice.repository.PracticeAssetRepository;
import com.micon.backend.practice.repository.PracticeAssetRepository.MediaRow;
import com.micon.backend.practice.repository.PracticeAssetRepository.NoteRow;
import com.micon.backend.practice.repository.PracticeAssetRepository.SongAssetRow;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PracticeAssetService {

	private final PracticeAssetRepository repository;
	private final ObjectMapper objectMapper;

	public PracticeAssetService(PracticeAssetRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public PracticeAssetResponse.Detail get(Long songId, int selectedKey) {
		SongAssetRow song = repository.findSong(songId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SONG_NOT_FOUND));
		if (!song.practiceReady() || song.analysisId() == null) {
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}

		MediaRow media = repository.findInstrumental(songId, selectedKey)
			.orElseThrow(() -> new BusinessException(selectedKey == 0
				? ErrorCode.PRACTICE_ASSET_NOT_READY
				: ErrorCode.UNSUPPORTED_PRACTICE_KEY));
		NoteRow note = repository.findNotes(song.analysisId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY));
		if (song.lyricsSynced() == null || song.lyricsSynced().isBlank()) {
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}

		return new PracticeAssetResponse.Detail(
			song.songId(),
			song.title(),
			selectedKey,
			new PracticeAssetResponse.Media(
				media.mediaId(), media.filePath(), media.mimeType(), media.fileBytes(), media.sha256()
			),
			song.lyricsPlain(),
			readJson(song.lyricsSynced()),
			readJson(note.karaokeNotes()),
			note.karaokeVersion() == null ? song.analysisVersion() : note.karaokeVersion(),
			repository.findSections(songId, song.analysisId())
		);
	}

	public PracticeAssetResponse.Guide getGuide(Long songId, int selectedKey) {
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

	private JsonNode readJson(String value) {
		try {
			return objectMapper.readTree(value);
		} catch (Exception exception) {
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}
	}
}
