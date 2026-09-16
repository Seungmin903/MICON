package com.micon.backend.practice.repository;

import com.micon.backend.practice.api.PracticeRequest.PracticeScope;
import com.micon.backend.practice.api.PracticeResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** practice_session의 생성과 상태 변경, 연습 곡·녹음·분석 참조 검증을 담당한다. */
@Repository
public class PracticeRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeRepository 객체를 생성한다.
	public PracticeRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findPracticeReadySong 조건으로 데이터베이스를 조회한다.
	public Optional<SongRow> findPracticeReadySong(Long songId) {
		String sql = """
				SELECT s.song_id, s.duration_ms,
				       CASE
				           WHEN ready_analysis.analysis_id IS NOT NULL
				            AND sm.lyrics_synced IS NOT NULL
				            AND EXISTS (
				                SELECT 1
				                FROM song_note ready_note
				                WHERE ready_note.analysis_id = ready_analysis.analysis_id
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
				       END AS practice_ready
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				LEFT JOIN song_analysis ready_analysis ON ready_analysis.analysis_id = (
				    SELECT latest.analysis_id
				    FROM song_analysis latest
				    WHERE latest.song_id = s.song_id
				      AND latest.status IN ('COMPLETED', 'PARTIAL')
				    ORDER BY latest.analyzed_at DESC, latest.analysis_id DESC
				    LIMIT 1
				)
				WHERE s.song_id = :songId
				""";
		return jdbcTemplate.query(sql, Map.of("songId", songId), (resultSet, rowNumber) -> new SongRow(
			resultSet.getLong("song_id"),
			resultSet.getObject("duration_ms", Long.class),
			resultSet.getBoolean("practice_ready")
		)).stream().findFirst();
	}

	// 기능: isUploadedRecordingOwnedBy 조건으로 데이터베이스를 조회한다.
	public boolean isUploadedRecordingOwnedBy(Long recordingId, Long userId) {
		String sql = """
				SELECT COUNT(*)
				FROM voice_recording
				WHERE recording_id = :recordingId
				  AND user_id = :userId
				  AND upload_status = 'UPLOADED'
				""";
		Long count = jdbcTemplate.queryForObject(sql,
			Map.of("recordingId", recordingId, "userId", userId), Long.class);
		return count != null && count > 0;
	}

	// 기능: sectionBelongsToSong에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean sectionBelongsToSong(Long sectionId, Long songId) {
		String sql = "SELECT COUNT(*) FROM song_section WHERE section_id = :sectionId AND song_id = :songId";
		Long count = jdbcTemplate.queryForObject(sql,
			Map.of("sectionId", sectionId, "songId", songId), Long.class);
		return count != null && count > 0;
	}

	// 기능: hasReadyInstrumental 조건으로 데이터베이스를 조회한다.
	public boolean hasReadyInstrumental(Long songId, int selectedKey) {
		String sql = """
				SELECT COUNT(*)
				FROM song_media
				WHERE song_id = :songId
				  AND semitones = :selectedKey
				  AND generation_status = 'READY'
				  AND ((:selectedKey = 0 AND kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY'))
				       OR (:selectedKey <> 0 AND kind = 'INSTRUMENTAL_KEY'))
				""";
		Long count = jdbcTemplate.queryForObject(sql,
			Map.of("songId", songId, "selectedKey", selectedKey), Long.class);
		return count != null && count > 0;
	}

	// 기능: create 데이터를 데이터베이스에 저장한다.
	public void create(
		Long practiceId,
		Long userId,
		Long songId,
		Long sectionId,
		Long recordingId,
		int selectedKey,
		BigDecimal playbackSpeed,
		Integer customStartMs,
		Integer customEndMs,
		PracticeScope scope
	) {
		String sql = """
				INSERT INTO practice_session (
				    practice_id, user_id, song_id, section_id, recording_id,
				    selected_key, playback_speed, custom_start_ms, custom_end_ms,
				    status, started_at, created_at, practice_scope
				) VALUES (
				    :practiceId, :userId, :songId, :sectionId, :recordingId,
				    :selectedKey, :playbackSpeed, :customStartMs, :customEndMs,
				    'READY', NULL, CURRENT_TIMESTAMP(6), :practiceScope
				)
				""";
		jdbcTemplate.update(sql, new MapSqlParameterSource()
			.addValue("practiceId", practiceId)
			.addValue("userId", userId)
			.addValue("songId", songId)
			.addValue("sectionId", sectionId)
			.addValue("recordingId", recordingId)
			.addValue("selectedKey", selectedKey)
			.addValue("playbackSpeed", playbackSpeed)
			.addValue("customStartMs", customStartMs)
			.addValue("customEndMs", customEndMs)
			.addValue("practiceScope", scope.name()));
	}

	// 기능: findOwned 조건으로 데이터베이스를 조회한다.
	public Optional<PracticeResponse.Detail> findOwned(Long practiceId, Long userId) {
		String sql = """
				SELECT ps.practice_id, ps.song_id, s.title AS song_title, ps.section_id,
				       ps.recording_id, ps.selected_key, ps.playback_speed,
				       ps.custom_start_ms, ps.custom_end_ms, ps.practice_scope, ps.status,
				       ps.started_at, ps.ended_at, ps.duration_ms, ps.created_at
				FROM practice_session ps
				JOIN song s ON s.song_id = ps.song_id
				WHERE ps.practice_id = :practiceId AND ps.user_id = :userId
				""";
		return jdbcTemplate.query(sql, Map.of("practiceId", practiceId, "userId", userId),
			(resultSet, rowNumber) -> new PracticeResponse.Detail(
				resultSet.getLong("practice_id"),
				resultSet.getLong("song_id"),
				resultSet.getString("song_title"),
				resultSet.getObject("section_id", Long.class),
				resultSet.getObject("recording_id", Long.class),
				null,
				resultSet.getInt("selected_key"),
				resultSet.getBigDecimal("playback_speed"),
				resultSet.getObject("custom_start_ms", Integer.class),
				resultSet.getObject("custom_end_ms", Integer.class),
				resultSet.getString("practice_scope"),
				resultSet.getString("status"),
				resultSet.getObject("started_at", LocalDateTime.class),
				resultSet.getObject("ended_at", LocalDateTime.class),
				resultSet.getObject("duration_ms", Integer.class),
				resultSet.getObject("created_at", LocalDateTime.class)
			)).stream().findFirst();
	}

	// 기능: complete에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public int complete(
		Long practiceId,
		Long userId,
		int durationMs,
		Long voiceAnalysisId,
		Long recordingId
	) {
		String sql = """
				UPDATE practice_session
				SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(6),
				    duration_ms = :durationMs,
				    recording_id = COALESCE(:recordingId, recording_id)
				WHERE practice_id = :practiceId AND user_id = :userId
				  AND status IN ('READY', 'RECORDING', 'IN_PROGRESS', 'PAUSED')
				""";
		return jdbcTemplate.update(sql, new MapSqlParameterSource()
			.addValue("practiceId", practiceId)
			.addValue("userId", userId)
			.addValue("durationMs", durationMs)
			.addValue("recordingId", recordingId));
	}

	// 기능: cancel에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public int cancel(Long practiceId, Long userId) {
		String sql = """
				UPDATE practice_session
				SET status = 'ABORTED', ended_at = CURRENT_TIMESTAMP(6)
				WHERE practice_id = :practiceId AND user_id = :userId
				  AND status IN ('READY', 'RECORDING', 'IN_PROGRESS', 'PAUSED')
				""";
		return jdbcTemplate.update(sql, Map.of("practiceId", practiceId, "userId", userId));
	}

	// 기능: delete 대상 데이터를 데이터베이스에서 삭제한다.
	public int delete(Long practiceId, Long userId) {
		String sql = "DELETE FROM practice_session WHERE practice_id = :practiceId AND user_id = :userId";
		return jdbcTemplate.update(sql, Map.of("practiceId", practiceId, "userId", userId));
	}

	// 기능: updateStatus 대상 데이터를 데이터베이스에서 변경한다.
	public int updateStatus(Long practiceId, Long userId, String status) {
		String sql = """
				UPDATE practice_session
				SET status = :status,
				    started_at = CASE
				        WHEN status = 'READY' AND :status IN ('RECORDING', 'IN_PROGRESS')
				        THEN CURRENT_TIMESTAMP(6)
				        ELSE started_at
				    END
				WHERE practice_id = :practiceId
				  AND user_id = :userId
				  AND (
				      (status = 'READY' AND :status IN ('RECORDING', 'IN_PROGRESS'))
				      OR (status IN ('RECORDING', 'IN_PROGRESS') AND :status = 'PAUSED')
				      OR (status = 'PAUSED' AND :status IN ('RECORDING', 'IN_PROGRESS'))
				  )
				""";
		return jdbcTemplate.update(sql, Map.of(
			"practiceId", practiceId,
			"userId", userId,
			"status", status
		));
	}

	// 기능: identifierExists에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean identifierExists(Long practiceId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_session WHERE practice_id = :practiceId",
			Map.of("practiceId", practiceId), Long.class);
		return count != null && count > 0;
	}

	public record SongRow(Long songId, Long durationMs, boolean practiceReady) {
	}

}
