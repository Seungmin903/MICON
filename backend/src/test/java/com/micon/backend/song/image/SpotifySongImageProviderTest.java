package com.micon.backend.song.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.domain.song.image.SpotifyAccessTokenProvider;
import com.micon.backend.domain.song.image.SpotifyImageProperties;
import com.micon.backend.domain.song.image.SpotifySongImageProvider;
import com.micon.backend.domain.song.image.SpotifyTrackImage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpotifySongImageProviderTest {

	private HttpServer server;

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void reusesAccessTokenAndReturnsLargestImageForExactTrack() throws Exception {
		AtomicInteger tokenRequests = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/api/token", exchange -> {
			tokenRequests.incrementAndGet();
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
			respond(exchange, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
		});
		server.createContext("/v1/search", exchange -> {
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
			String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
			assertThat(query).contains("q=track:Good Day artist:IU", "type=track", "market=KR");
			respond(exchange, """
				{"tracks":{"items":[
				  {"name":"Good Day","artists":[{"name":"Other"}],"album":{"images":[{"url":"https://wrong","width":640,"height":640}]},"external_urls":{"spotify":"https://open.spotify.com/track/wrong"}},
				  {"name":"Good Day","artists":[{"name":"IU"}],"album":{"images":[
				    {"url":"https://i.scdn.co/image/small","width":64,"height":64},
				    {"url":"https://i.scdn.co/image/large","width":640,"height":640}
				  ]},"external_urls":{"spotify":"https://open.spotify.com/track/correct"}}
				]}}
				""");
		});
		server.start();

		SpotifyImageProperties properties = properties();
		SpotifyAccessTokenProvider tokenProvider = new SpotifyAccessTokenProvider(properties);
		SpotifySongImageProvider provider = new SpotifySongImageProvider(properties, tokenProvider);

		assertThat(provider.findImage("Good Day", "IU")).contains(
			new SpotifyTrackImage(
				"https://i.scdn.co/image/large",
				"https://open.spotify.com/track/correct"
			)
		);
		assertThat(provider.findImage("Good Day", "IU")).isPresent();
		assertThat(tokenRequests).hasValue(1);
	}

	@Test
	void returnsImageBySpotifyTrackIdWithoutTitleMatching() throws Exception {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/api/token", exchange ->
			respond(exchange, "{\"access_token\":\"token-1\",\"expires_in\":3600}"));
		server.createContext("/v1/tracks/3P3UA61WRQqwCXaoFOTENd", exchange -> {
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
			assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("market=KR");
			respond(exchange, """
				{"name":"Through the Night","album":{"images":[
				  {"url":"https://i.scdn.co/image/large","width":640,"height":640}
				]},"external_urls":{"spotify":"https://open.spotify.com/track/3P3UA61WRQqwCXaoFOTENd"}}
				""");
		});
		server.start();

		SpotifyImageProperties properties = properties();
		SpotifySongImageProvider provider = new SpotifySongImageProvider(
			properties,
			new SpotifyAccessTokenProvider(properties)
		);

		assertThat(provider.findImageByTrackId("3P3UA61WRQqwCXaoFOTENd")).contains(
			new SpotifyTrackImage(
				"https://i.scdn.co/image/large",
				"https://open.spotify.com/track/3P3UA61WRQqwCXaoFOTENd"
			)
		);
	}

	private SpotifyImageProperties properties() {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return new SpotifyImageProperties(
			true,
			baseUrl,
			baseUrl,
			"client-id",
			"client-secret",
			"KR",
			3,
			Duration.ofHours(6),
			Duration.ofMinutes(10),
			5000,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1)
		);
	}

	private void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}
}
