package com.micon.backend.domain.preference.repository;

import com.micon.backend.domain.preference.dto.PreferenceResponse.Artist;
import com.micon.backend.domain.preference.dto.PreferenceResponse.Genre;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreferenceCatalogRepository {
	private static final String GENRES = """
		SELECT genre_id, COALESCE(preference_label, name) AS name, normalized_name
		FROM genre
		""";
	private static final RowMapper<Genre> GENRE = (rs, row) ->
		new Genre(rs.getLong("genre_id"), rs.getString("name"), rs.getString("normalized_name"));
	// Curated portraits are reviewed and carry their source/license metadata. They take
	// precedence over legacy singer.image_url values, which may point at stale/wrong art.
	// Keep the legacy URL as a fallback for singers that are not in the portrait catalog.
	private static final String ARTISTS = """
		SELECT s.singer_id, COALESCE(p.display_name, s.name) AS name,
		       COALESCE(NULLIF(p.image_url, ''), NULLIF(s.image_url, '')) AS image_url,
		       CASE WHEN NULLIF(p.image_url, '') IS NOT NULL THEN p.source_url END AS source_url,
		       CASE WHEN NULLIF(p.image_url, '') IS NOT NULL THEN p.attribution END AS attribution,
		       CASE WHEN NULLIF(p.image_url, '') IS NOT NULL THEN p.license END AS license,
		       CASE WHEN NULLIF(p.image_url, '') IS NOT NULL THEN p.license_url END AS license_url
		FROM singer s
		LEFT JOIN artist_portrait p ON p.normalized_name = s.normalized_name
		""";
	private static final String SEARCH = """
		WHERE LOWER(s.name) LIKE :queryPattern ESCAPE '!'
		   OR LOWER(p.search_names) LIKE :queryPattern ESCAPE '!'
		""";
	private static final RowMapper<Artist> ARTIST = (rs, row) -> new Artist(
		rs.getLong("singer_id"), rs.getString("name"), rs.getString("image_url"),
		rs.getString("source_url"), rs.getString("attribution"), rs.getString("license"), rs.getString("license_url"));
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public PreferenceCatalogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Genre> findGenres() {
		return jdbcTemplate.query(GENRES + " WHERE preference_order IS NOT NULL ORDER BY preference_order, genre_id", Map.of(), GENRE);
	}

	public List<Genre> findGenresByIds(List<Long> genreIds) {
		if (genreIds.isEmpty()) return List.of();
		return jdbcTemplate.query(GENRES + " WHERE genre_id IN (:genreIds) ORDER BY genre_id", Map.of("genreIds", genreIds), GENRE);
	}

	public List<Genre> findSelectableGenresByIds(List<Long> genreIds) {
		if (genreIds.isEmpty()) return List.of();
		return jdbcTemplate.query(GENRES + " WHERE preference_order IS NOT NULL AND genre_id IN (:genreIds)", Map.of("genreIds", genreIds), GENRE);
	}

	public List<Artist> findArtistsByIds(List<Long> artistIds) {
		if (artistIds.isEmpty()) return List.of();
		return jdbcTemplate.query(ARTISTS + " WHERE s.singer_id IN (:artistIds) ORDER BY s.singer_id", Map.of("artistIds", artistIds), ARTIST);
	}

	public List<Artist> findArtists(String query, int size, long offset) {
		return jdbcTemplate.query(ARTISTS + SEARCH + """
			ORDER BY CASE WHEN COALESCE(NULLIF(p.image_url, ''), NULLIF(s.image_url, '')) IS NULL THEN 1 ELSE 0 END,
			 (SELECT COUNT(*) FROM song_singer ss JOIN song song ON song.song_id = ss.song_id
			  WHERE ss.singer_id = s.singer_id AND song.status = 'ACTIVE') DESC,
			 COALESCE(p.display_name, s.name), s.singer_id
			LIMIT :size OFFSET :offset
			""", searchParameters(query).addValue("size", size).addValue("offset", offset), ARTIST);
	}

	public long countArtists(String query) {
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM singer s
			LEFT JOIN artist_portrait p ON p.normalized_name = s.normalized_name
			""" + SEARCH, searchParameters(query), Long.class);
		return count == null ? 0 : count;
	}

	private MapSqlParameterSource searchParameters(String query) {
		String normalized = query.trim().toLowerCase(Locale.ROOT)
			.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return new MapSqlParameterSource("queryPattern", "%" + normalized + "%");
	}
}
