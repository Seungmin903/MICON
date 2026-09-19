package com.micon.backend.domain.preference.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PreferenceRequest(
	@NotNull @Size(max = 20) List<@NotNull @Positive Long> genreIds,
	@NotNull @Size(max = 50) List<@NotNull @Positive Long> artistIds,
	@NotNull Boolean onboardingCompleted
) {
}
