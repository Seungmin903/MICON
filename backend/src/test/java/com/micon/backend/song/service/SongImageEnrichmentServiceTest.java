package com.micon.backend.song.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.song.api.SongResponse;
import com.micon.backend.song.image.SpotifyImageProperties;
import com.micon.backend.song.image.SpotifySongImageProvider;
import com.micon.backend.song.image.SpotifyTrackImage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SongImageEnrichmentServiceTest {

	@Mock
	private SpotifySongImageProvider imageProvider;

	@Mock
	private SongImageCacheService cacheService;

	@Test
	void enrichesAndCachesMissingImage() {
		SongImageEnrichmentService service = service(3);
		SpotifyTrackImage image = new SpotifyTrackImage(
			"https://i.scdn.co/image/good-day",
			"https://open.spotify.com/track/good-day"
		);
		when(imageProvider.findImage("좋은 날", "아이유")).thenReturn(Optional.of(image));

		List<SongResponse.Summary> result = service.enrich(List.of(song("tj-1", null)));

		assertThat(result).singleElement().satisfies(enriched -> {
			assertThat(enriched.imageUrl()).isEqualTo(image.imageUrl());
			assertThat(enriched.imageSourceUrl()).isEqualTo(image.sourceUrl());
		});
		verify(cacheService).cache("tj-1", image);
	}

	@Test
	void replacesExistingDatabaseImageWithSpotifyImage() {
		SongImageEnrichmentService service = service(3);
		SongResponse.Summary song = song("tj-1", "https://existing.example/image.jpg");
		SpotifyTrackImage image = new SpotifyTrackImage(
			"https://i.scdn.co/image/good-day",
			"https://open.spotify.com/track/good-day"
		);
		when(imageProvider.findImage("좋은 날", "아이유")).thenReturn(Optional.of(image));

		assertThat(service.enrich(List.of(song))).singleElement().satisfies(enriched -> {
			assertThat(enriched.imageUrl()).isEqualTo(image.imageUrl());
			assertThat(enriched.imageSourceUrl()).isEqualTo(image.sourceUrl());
		});
		verify(imageProvider).findImage("좋은 날", "아이유");
	}

	@Test
	void limitsSpotifyCallsForOneListRequest() {
		SongImageEnrichmentService service = service(1);
		when(imageProvider.findImage("좋은 날", "아이유")).thenReturn(Optional.empty());

		service.enrich(List.of(song("tj-1", null), song("tj-2", null)));

		verify(imageProvider).findImage("좋은 날", "아이유");
		verify(imageProvider, never()).findImage("좋은 날 2", "아이유");
	}

	@Test
	void usesSpotifyTrackIdBeforeTitleSearch() {
		SongImageEnrichmentService service = service(3);
		SpotifyTrackImage image = new SpotifyTrackImage(
			"https://i.scdn.co/image/through-the-night",
			"https://open.spotify.com/track/3P3UA61WRQqwCXaoFOTENd"
		);
		when(imageProvider.findImageByTrackId("3P3UA61WRQqwCXaoFOTENd"))
			.thenReturn(Optional.of(image));

		List<SongResponse.Summary> result = service.enrich(
			List.of(new SongResponse.Summary("song-1", "밤편지", List.of("아이유"), null, null, 253_000L)),
			Map.of("song-1", "3P3UA61WRQqwCXaoFOTENd")
		);

		assertThat(result).singleElement().satisfies(enriched ->
			assertThat(enriched.imageUrl()).isEqualTo(image.imageUrl())
		);
		verify(imageProvider).findImageByTrackId("3P3UA61WRQqwCXaoFOTENd");
		verify(imageProvider, never()).findImage("밤편지", "아이유");
	}

	private SongImageEnrichmentService service(int maxLookups) {
		SpotifyImageProperties properties = new SpotifyImageProperties(
			true,
			"https://accounts.spotify.com",
			"https://api.spotify.com",
			"client-id",
			"client-secret",
			"KR",
			maxLookups,
			Duration.ofHours(6),
			Duration.ofMinutes(10),
			5000,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1)
		);
		return new SongImageEnrichmentService(imageProvider, properties, cacheService);
	}

	private SongResponse.Summary song(String songId, String imageUrl) {
		String title = songId.equals("tj-1") ? "좋은 날" : "좋은 날 2";
		return new SongResponse.Summary(songId, title, List.of("아이유"), imageUrl, null, 233_000L);
	}
}
