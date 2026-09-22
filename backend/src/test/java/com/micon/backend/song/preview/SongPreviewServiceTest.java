package com.micon.backend.song.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.song.preview.SongPreviewProperties;
import com.micon.backend.domain.song.preview.SongPreviewService;
import com.micon.backend.domain.song.repository.SongQueryRepository;
import com.micon.backend.domain.song.repository.SongQueryRepository.SongDetailRow;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SongPreviewServiceTest {

    private static final String SONG_ID = "d221afea-6dc1-5223-a6bc-68fa055b4820";
    private static final String PREFIX = "catalog/정다운/source-preview-pilot-20260922/";
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock SongQueryRepository songs;
    @Mock VoiceStorage storage;
    SongPreviewService service;

    @BeforeEach
    void setUp() {
        service = new SongPreviewService(songs, storage,
                new SongPreviewProperties(PREFIX, Duration.ofMinutes(10)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signsOnlyTheExpectedSourcePreviewObject() {
        String objectKey = PREFIX + SONG_ID + "-preview-60s.mp3";
        when(songs.findDetail(SONG_ID)).thenReturn(Optional.of(song()));
        when(storage.exists(objectKey)).thenReturn(true);
        when(storage.createDownloadUrl(objectKey, Duration.ofMinutes(10)))
                .thenReturn(URI.create("https://signed.example/preview"));

        var response = service.get(SONG_ID);

        assertThat(response.songId()).isEqualTo(SONG_ID);
        assertThat(response.previewSeconds()).isEqualTo(60);
        assertThat(response.playbackUrl()).isEqualTo("https://signed.example/preview");
        assertThat(response.playbackUrlExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        verify(storage).exists(objectKey);
    }

    @Test
    void rejectsUnavailablePreviewBeforeSigning() {
        when(songs.findDetail(SONG_ID)).thenReturn(Optional.of(song()));
        when(storage.exists(PREFIX + SONG_ID + "-preview-60s.mp3")).thenReturn(false);

        assertError(SONG_ID, ErrorCode.SONG_PREVIEW_NOT_READY);
        verify(storage, never()).createDownloadUrl(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingSongBeforeStorageLookup() {
        when(songs.findDetail(SONG_ID)).thenReturn(Optional.empty());

        assertError(SONG_ID, ErrorCode.SONG_NOT_FOUND);
        verify(storage, never()).exists(org.mockito.ArgumentMatchers.anyString());
    }

    private void assertError(String songId, ErrorCode expected) {
        assertThatThrownBy(() -> service.get(songId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private static SongDetailRow song() {
        return new SongDetailRow(SONG_ID, "테스트", 60_000L, null, true,
                null, LocalDate.of(2020, 1, 1), null, null,
                BigDecimal.valueOf(48), BigDecimal.valueOf(72));
    }
}
