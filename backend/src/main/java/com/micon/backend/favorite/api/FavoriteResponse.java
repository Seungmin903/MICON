package com.micon.backend.favorite.api;

import com.micon.backend.song.api.SongResponse;
import java.util.List;

public record FavoriteResponse(
	List<SongResponse.Summary> songs,
	int totalCount
) {
}
