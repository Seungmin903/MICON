package com.micon.backend.domain.song.image;

/** Spotify 검색으로 찾은 원본 앨범 커버와 해당 트랙 링크다. */
public record SpotifyTrackImage(String imageUrl, String sourceUrl) {
}
