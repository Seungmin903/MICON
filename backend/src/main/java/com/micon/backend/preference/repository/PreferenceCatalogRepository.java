package com.micon.backend.preference.repository;

import com.micon.backend.preference.api.PreferenceResponse.Artist;
import com.micon.backend.preference.api.PreferenceResponse.Genre;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreferenceCatalogRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public PreferenceCatalogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Genre> findGenres() {
		return jdbcTemplate.query("SELECT genre_id, name FROM GENRE ORDER BY name, genre_id", Map.of(),
			(resultSet, rowNumber) -> new Genre(
				resultSet.getLong("genre_id"),
				resultSet.getString("name")
			));
	}

	public List<Genre> findGenresByIds(List<Long> genreIds) {
		if (genreIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query("""
			SELECT genre_id, name
			FROM GENRE
			WHERE genre_id IN (:genreIds)
			ORDER BY genre_id
			""", Map.of("genreIds", genreIds),
			(resultSet, rowNumber) -> new Genre(
				resultSet.getLong("genre_id"),
				resultSet.getString("name")
			));
	}

	public List<Artist> findArtistsByIds(List<Long> artistIds) {
		if (artistIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query("""
			SELECT singer_id, name, image_url
			FROM SINGER
			WHERE singer_id IN (:artistIds)
			ORDER BY singer_id
			""", Map.of("artistIds", artistIds),
			(resultSet, rowNumber) -> new Artist(
				resultSet.getLong("singer_id"),
				resultSet.getString("name"),
				resultSet.getString("image_url")
			));
	}

	public List<Artist> findArtists(String query, int size, long offset) {
		return jdbcTemplate.query("""
			SELECT singer_id, name, image_url
			FROM SINGER
			WHERE LOWER(name) LIKE :queryPattern ESCAPE '!'
			ORDER BY name, singer_id
			LIMIT :size OFFSET :offset
			""", searchParameters(query)
			.addValue("size", size)
			.addValue("offset", offset),
			(resultSet, rowNumber) -> new Artist(
				resultSet.getLong("singer_id"),
				resultSet.getString("name"),
				resultSet.getString("image_url")
			));
	}

	public long countArtists(String query) {
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM SINGER
			WHERE LOWER(name) LIKE :queryPattern ESCAPE '!'
			""", searchParameters(query), Long.class);
		return count == null ? 0 : count;
	}

	private MapSqlParameterSource searchParameters(String query) {
		String normalized = query.trim().toLowerCase(Locale.ROOT)
			.replace("!", "!!")
			.replace("%", "!%")
			.replace("_", "!_");
		return new MapSqlParameterSource("queryPattern", "%" + normalized + "%");
	}
}
