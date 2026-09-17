package com.micon.backend.homepage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 홈페이지에서 곡별 연습 데이터 준비 상태를 한 번에 조회한다. */
@Repository
public class HomepageSongRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public HomepageSongRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Map<String, HomepageSongStatus> findStatuses(List<String> songIds) {
		if (songIds.isEmpty()) {
			return Map.of();
		}
		String sql = """
			SELECT s.song_id,
			       s.practice_ready AS stored_practice_ready,
			       CASE WHEN sm.lyrics_plain IS NOT NULL AND CHAR_LENGTH(TRIM(sm.lyrics_plain)) > 0
			            THEN TRUE ELSE FALSE END AS lyrics_plain_ready,
			       CASE WHEN sm.lyrics_synced IS NOT NULL
			            THEN TRUE ELSE FALSE END AS lyrics_synced_ready,
			       CASE WHEN ready_analysis.analysis_id IS NOT NULL
			            THEN TRUE ELSE FALSE END AS analysis_ready,
			       CASE WHEN EXISTS (
			           SELECT 1 FROM song_note ready_note
			           WHERE ready_note.analysis_id = ready_analysis.analysis_id
			             AND ready_note.karaoke_notes IS NOT NULL
			       ) THEN TRUE ELSE FALSE END AS target_notes_ready,
			       CASE WHEN EXISTS (
			           SELECT 1 FROM song_media ready_media
			           WHERE ready_media.song_id = s.song_id
			             AND ready_media.generation_status = 'READY'
			             AND ready_media.semitones = 0
			             AND ready_media.kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY')
			       ) THEN TRUE ELSE FALSE END AS original_instrumental_ready,
			       CASE WHEN ready_analysis.analysis_id IS NOT NULL
			              AND sm.lyrics_synced IS NOT NULL
			              AND EXISTS (
			                  SELECT 1 FROM song_note ready_note
			                  WHERE ready_note.analysis_id = ready_analysis.analysis_id
			                    AND ready_note.karaoke_notes IS NOT NULL
			              )
			              AND EXISTS (
			                  SELECT 1 FROM song_media ready_media
			                  WHERE ready_media.song_id = s.song_id
			                    AND ready_media.generation_status = 'READY'
			                    AND ready_media.semitones = 0
			                    AND ready_media.kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY')
			              )
			            THEN TRUE ELSE FALSE END AS practice_ready
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
			WHERE s.song_id IN (:songIds)
			""";
		Map<String, HomepageSongStatus> statuses = new HashMap<>();
		jdbcTemplate.query(sql, Map.of("songIds", songIds), resultSet -> {
			HomepageSongStatus status = new HomepageSongStatus(
				resultSet.getString("song_id"),
				resultSet.getBoolean("stored_practice_ready"),
				resultSet.getBoolean("practice_ready"),
				resultSet.getBoolean("lyrics_plain_ready"),
				resultSet.getBoolean("lyrics_synced_ready"),
				resultSet.getBoolean("analysis_ready"),
				resultSet.getBoolean("target_notes_ready"),
				resultSet.getBoolean("original_instrumental_ready")
			);
			statuses.put(status.songId(), status);
		});
		return statuses;
	}
}
