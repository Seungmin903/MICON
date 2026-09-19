package com.micon.backend.domain.preference.dto;

import java.util.List;

public record PreferenceResponse(
	List<Long> genreIds,
	List<Long> artistIds,
	List<Genre> genres,
	List<Artist> artists,
	boolean onboardingCompleted
) {
	public record Genre(Long genreId, String name, String code) {
	}

	public record Artist(Long artistId, String name, String imageUrl,
		String imageSourceUrl, String imageAttribution, String imageLicense, String imageLicenseUrl) {
	}
}
