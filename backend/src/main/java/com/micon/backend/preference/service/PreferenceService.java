package com.micon.backend.preference.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.preference.api.PreferenceRequest;
import com.micon.backend.preference.api.PreferenceResponse;
import com.micon.backend.preference.api.PreferenceResponse.Artist;
import com.micon.backend.preference.api.PreferenceResponse.Genre;
import com.micon.backend.preference.repository.PreferenceCatalogRepository;
import com.micon.backend.preference.repository.UserPreferenceRepository;
import com.micon.backend.song.api.PageResponse;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferenceService {

	private static final int MAX_GENRES = 20;
	private static final int MAX_ARTISTS = 50;

	private final UserRepository userRepository;
	private final PreferenceCatalogRepository catalogRepository;
	private final UserPreferenceRepository preferenceRepository;

	public PreferenceService(
		UserRepository userRepository,
		PreferenceCatalogRepository catalogRepository,
		UserPreferenceRepository preferenceRepository
	) {
		this.userRepository = userRepository;
		this.catalogRepository = catalogRepository;
		this.preferenceRepository = preferenceRepository;
	}

	@Transactional(readOnly = true)
	public List<Genre> getGenres(long userId) {
		requireActiveUser(userId);
		return catalogRepository.findGenres();
	}

	@Transactional(readOnly = true)
	public PageResponse<Artist> getArtists(long userId, String query, int page, int size) {
		requireActiveUser(userId);
		String safeQuery = query == null ? "" : query;
		if (safeQuery.length() > 100 || page < 0 || size < 1 || size > 100) {
			throw invalid("검색어는 100자 이하, page는 0 이상, size는 1 이상 100 이하여야 합니다.");
		}
		long totalElements = catalogRepository.countArtists(safeQuery);
		List<Artist> artists = totalElements == 0
			? List.of()
			: catalogRepository.findArtists(safeQuery, size, (long) page * size);
		return PageResponse.of(artists, page, size, totalElements);
	}

	@Transactional(readOnly = true)
	public PreferenceResponse getPreferences(long userId) {
		requireActiveUser(userId);
		return read(userId);
	}

	@Transactional
	public PreferenceResponse savePreferences(long userId, PreferenceRequest request) {
		userRepository.findByIdForUpdate(userId)
			.filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));

		validateIds(request.genreIds(), MAX_GENRES, "장르");
		validateIds(request.artistIds(), MAX_ARTISTS, "아티스트");

		if (!request.onboardingCompleted() && preferenceRepository.isOnboardingCompleted(userId)) {
			return read(userId);
		}
		if (request.onboardingCompleted()
			&& (request.genreIds().isEmpty() || request.artistIds().isEmpty())) {
			throw invalid("관심 장르와 아티스트를 각각 1개 이상 선택해 주세요.");
		}
		if (catalogRepository.findSelectableGenresByIds(request.genreIds()).size() != request.genreIds().size()
			|| catalogRepository.findArtistsByIds(request.artistIds()).size() != request.artistIds().size()) {
			throw invalid("존재하지 않는 장르 또는 아티스트입니다.");
		}

		preferenceRepository.replace(userId, request.genreIds(), request.artistIds());
		if (request.onboardingCompleted()) {
			preferenceRepository.markOnboardingCompleted(userId);
		}
		return read(userId);
	}

	private PreferenceResponse read(long userId) {
		List<Long> genreIds = preferenceRepository.findGenreIds(userId);
		List<Long> artistIds = preferenceRepository.findArtistIds(userId);
		return new PreferenceResponse(
			genreIds,
			artistIds,
			catalogRepository.findGenresByIds(genreIds),
			catalogRepository.findArtistsByIds(artistIds),
			preferenceRepository.isOnboardingCompleted(userId)
		);
	}

	private void requireActiveUser(long userId) {
		if (userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE).isEmpty()) {
			throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
		}
	}

	private void validateIds(List<Long> ids, int maximum, String label) {
		if (ids == null || ids.size() > maximum || ids.stream().anyMatch(id -> id == null || id <= 0)
			|| new HashSet<>(ids).size() != ids.size()) {
			throw invalid(label + " 선택 값이 올바르지 않습니다.");
		}
	}

	private BusinessException invalid(String message) {
		return new BusinessException(ErrorCode.INVALID_REQUEST, message);
	}
}
