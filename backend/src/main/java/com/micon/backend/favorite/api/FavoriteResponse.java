package com.micon.backend.favorite.api;

import com.micon.backend.song.api.SongResponse;
import java.util.List;

/** 회원이 찜한 곡 목록과 전체 개수를 담는 응답 DTO다. */
public record FavoriteResponse(
	List<SongResponse.Summary> songs,
	int totalCount
) {
}
