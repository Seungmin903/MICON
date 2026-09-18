package com.micon.backend.domain.song.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Spotify 곡 이미지 설정을 Spring 구성 속성으로 등록한다. */
@Configuration
@EnableConfigurationProperties(SpotifyImageProperties.class)
public class SpotifyImageConfig {
}
