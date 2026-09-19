package com.micon.backend.catalogmedia;

import com.micon.backend.domain.catalogmedia.config.CatalogMediaProperties;
import com.micon.backend.domain.catalogmedia.dto.CatalogMediaResponse;
import com.micon.backend.domain.catalogmedia.repository.CatalogMediaRepository;
import com.micon.backend.domain.catalogmedia.service.CatalogMediaService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.domain.catalogmedia.repository.CatalogMediaRepository.VocalStemRow;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogMediaServiceTest {

    private static final String PREFIX = "catalog/정영주/section-384-v1/vocals/";
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Mock CatalogMediaRepository repository;
    @Mock VoiceStorage storage;
    CatalogMediaService service;

    @BeforeEach
    void setUp() {
        service = new CatalogMediaService(repository, storage,
                new CatalogMediaProperties(PREFIX, Duration.ofMinutes(10)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsShortLivedUrlForExactReadyVocalStem() {
        String key = PREFIX + "0156ac758f1192ee/vocals.wav";
        when(repository.songExists("212")).thenReturn(true);
        when(repository.findReadyVocalStem("212")).thenReturn(Optional.of(new VocalStemRow(
                10L, "212", "0156ac758f1192ee", key, "audio/wav", 37_875_760L, "a".repeat(64))));
        when(storage.createDownloadUrl(key, Duration.ofMinutes(10)))
                .thenReturn(URI.create("https://signed.example/vocal"));

        CatalogMediaResponse response = service.vocalStem("212");

        assertThat(response.songId()).isEqualTo("212");
        assertThat(response.catalogTrackId()).isEqualTo("0156ac758f1192ee");
        assertThat(response.mediaType()).isEqualTo("VOCAL_STEM");
        assertThat(response.contentUrl()).isEqualTo("https://signed.example/vocal");
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void rejectsMissingSongBeforeMediaLookup() {
        when(repository.songExists("999")).thenReturn(false);

        assertError("999", ErrorCode.SONG_NOT_FOUND);
        verify(repository, never()).findReadyVocalStem("999");
        verify(storage, never()).createDownloadUrl(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingOrUnreadyMedia() {
        when(repository.songExists("212")).thenReturn(true);
        when(repository.findReadyVocalStem("212")).thenReturn(Optional.empty());

        assertError("212", ErrorCode.CATALOG_MEDIA_NOT_READY);
    }

    @Test
    void refusesWrongPrefixWithoutSigningIt() {
        when(repository.songExists("212")).thenReturn(true);
        when(repository.findReadyVocalStem("212")).thenReturn(Optional.of(new VocalStemRow(
                10L, "212", "0156ac758f1192ee", "voice-recordings/private.wav",
                "audio/wav", 100L, "a".repeat(64))));

        assertError("212", ErrorCode.CATALOG_MEDIA_NOT_READY);
        verify(storage, never()).createDownloadUrl(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private void assertError(String songId, ErrorCode expected) {
        assertThatThrownBy(() -> service.vocalStem(songId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
