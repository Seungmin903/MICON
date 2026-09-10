package com.micon.backend.preference.api;

import java.util.List;

public record PreferenceResponse(
	List<Long> genreIds,
	List<Long> artistIds,
	List<Genre> genres,
	List<Artist> artists,
	boolean onboardingCompleted
) {
	public record Genre(Long genreId, String name) {
	}

	public record Artist(Long artistId, String name, String imageUrl) {
	}
}
