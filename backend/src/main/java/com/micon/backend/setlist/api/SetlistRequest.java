package com.micon.backend.setlist.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class SetlistRequest {

	private SetlistRequest() {
	}

	public record Create(
		@NotBlank @Size(max = 100) String name
	) {
	}

	public record Rename(
		@NotBlank @Size(max = 100) String name
	) {
	}

	public record Reorder(
		@NotNull List<@NotNull @Positive Long> songIds,
		LocalDateTime updatedAt
	) {
	}
}
