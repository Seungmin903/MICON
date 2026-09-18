package com.micon.backend.domain.setlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 세트리스트 생성·이름 변경·곡 순서 변경 요청 DTO와 입력 검증 규칙을 정의한다. */
public final class SetlistRequest {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
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
		@NotNull List<@NotBlank @Size(max = 64) String> songIds,
		LocalDateTime updatedAt
	) {
	}
}
