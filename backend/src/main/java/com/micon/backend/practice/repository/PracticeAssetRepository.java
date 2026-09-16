package com.micon.backend.practice.repository;

import com.micon.backend.practice.api.PracticeAssetResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 곡의 최신 분석·미디어·가사·목표 음정·연습 구간 데이터를 JDBC로 조회한다. */
@Repository
public class PracticeAssetRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeAssetRepository 객체를 생성한다.
	public PracticeAssetRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findSong 조건으로 데이터베이스를 조회한다.
	public Optional<SongAssetRow> findSong(Long songId) {
		String sql = """
				SELECT s.song_id, s.title,
				       CASE
				           WHEN sa.analysis_id IS NOT NULL
				            AND sm.lyrics_synced IS NOT NULL
				            AND EXISTS (
				                SELECT 1
				                FROM song_note ready_note
				                WHERE ready_note.analysis_id = sa.analysis_id
				                  AND ready_note.karaoke_notes IS NOT NULL
				            )
				            AND EXISTS (
				                SELECT 1
				                FROM song_media ready_media
				                WHERE ready_media.song_id = s.song_id
				                  AND ready_media.generation_status = 'READY'
				                  AND ready_media.semitones = 0
				                  AND ready_media.kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY')
				            )
				           THEN TRUE ELSE FALSE
				       END AS practice_ready,
				       sm.lyrics_plain, sm.lyrics_synced,
				       sa.analysis_id, sa.analysis_version, sa.key_name,
				       sa.tempo_bpm, sa.time_signature, sa.beat_grid
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				LEFT JOIN song_analysis sa ON sa.analysis_id = (
				    SELECT latest.analysis_id
				    FROM song_analysis latest
				    WHERE latest.song_id = s.song_id
				      AND latest.status IN ('COMPLETED', 'PARTIAL')
				    ORDER BY latest.analyzed_at DESC, latest.analysis_id DESC
				    LIMIT 1
				)
				WHERE s.song_id = :songId
				""";
		return jdbcTemplate.query(sql, Map.of("songId", songId), (resultSet, rowNumber) -> new SongAssetRow(
			resultSet.getLong("song_id"),
			resultSet.getString("title"),
			resultSet.getBoolean("practice_ready"),
			resultSet.getString("lyrics_plain"),
			resultSet.getString("lyrics_synced"),
			resultSet.getObject("analysis_id", Long.class),
			resultSet.getString("analysis_version"),
			resultSet.getString("key_name"),
			resultSet.getBigDecimal("tempo_bpm"),
			resultSet.getString("time_signature"),
			resultSet.getString("beat_grid")
		)).stream().findFirst();
	}

	// 기능: findInstrumental 조건으로 데이터베이스를 조회한다.
	public Optional<MediaRow> findInstrumental(Long songId, int selectedKey) {
		String sql = """
				SELECT media_id, file_path, mime_type, file_bytes, sha256
				FROM song_media
				WHERE song_id = :songId
				  AND generation_status = 'READY'
				  AND semitones = :selectedKey
				  AND ((:selectedKey = 0 AND kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY'))
				       OR (:selectedKey <> 0 AND kind = 'INSTRUMENTAL_KEY'))
				ORDER BY CASE WHEN kind = 'INSTRUMENTAL' THEN 0 ELSE 1 END, media_id
				LIMIT 1
				""";
		return jdbcTemplate.query(sql, Map.of("songId", songId, "selectedKey", selectedKey),
			(resultSet, rowNumber) -> new MediaRow(
				resultSet.getLong("media_id"),
				resultSet.getString("file_path"),
				resultSet.getString("mime_type"),
				resultSet.getObject("file_bytes", Long.class),
				resultSet.getString("sha256")
			)).stream().findFirst();
	}

	// 기능: findNotes 조건으로 데이터베이스를 조회한다.
	public Optional<NoteRow> findNotes(Long analysisId) {
		String sql = """
				SELECT karaoke_notes, karaoke_version
				FROM song_note
				WHERE analysis_id = :analysisId
				ORDER BY updated_at DESC, song_note_id DESC
				LIMIT 1
				""";
		return jdbcTemplate.query(sql, Map.of("analysisId", analysisId), (resultSet, rowNumber) -> new NoteRow(
			resultSet.getString("karaoke_notes"),
			resultSet.getString("karaoke_version")
		)).stream().findFirst();
	}

	// 기능: findSections 조건으로 데이터베이스를 조회한다.
	public List<PracticeAssetResponse.Section> findSections(Long songId, Long analysisId) {
		String sql = """
				SELECT section_id, section_type, section_name, start_ms, end_ms, sequence_no,
				       difficulty_type, difficulty_score, confidence, description
				FROM song_section
				WHERE song_id = :songId AND analysis_id = :analysisId
				ORDER BY sequence_no, section_id
				""";
		return jdbcTemplate.query(sql, Map.of("songId", songId, "analysisId", analysisId),
			(resultSet, rowNumber) -> new PracticeAssetResponse.Section(
				resultSet.getLong("section_id"),
				resultSet.getString("section_type"),
				resultSet.getString("section_name"),
				resultSet.getInt("start_ms"),
				resultSet.getInt("end_ms"),
				resultSet.getInt("sequence_no"),
				resultSet.getString("difficulty_type"),
				resultSet.getBigDecimal("difficulty_score"),
				resultSet.getBigDecimal("confidence"),
				resultSet.getString("description")
			));
	}

	public record SongAssetRow(
		Long songId,
		String title,
		boolean practiceReady,
		String lyricsPlain,
		String lyricsSynced,
		Long analysisId,
		String analysisVersion,
		String keyName,
		java.math.BigDecimal tempoBpm,
		String timeSignature,
		String beatGrid
	) {
	}

	public record MediaRow(Long mediaId, String filePath, String mimeType, Long fileBytes, String sha256) {
	}

	public record NoteRow(String karaokeNotes, String karaokeVersion) {
	}
}
