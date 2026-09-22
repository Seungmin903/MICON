package com.micon.backend.domain.song.preview;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.song.repository.SongQueryRepository;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 운영 카탈로그의 원곡 60초 미리듣기 URL을 발급한다. */
@Service
public class SongPreviewService {

    private static final Pattern SONG_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SongQueryRepository songs;
    private final VoiceStorage storage;
    private final SongPreviewProperties properties;
    private final Clock clock;

    public SongPreviewService(SongQueryRepository songs, VoiceStorage storage,
                              SongPreviewProperties properties, Clock clock) {
        this.songs = songs;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SongPreviewResponse get(String songId) {
        if (!songs.findDetail(songId).isPresent()) {
            throw new BusinessException(ErrorCode.SONG_NOT_FOUND);
        }
        if (!SONG_ID.matcher(songId).matches()) {
            throw new BusinessException(ErrorCode.SONG_PREVIEW_NOT_READY);
        }

        String objectKey = properties.objectPrefix() + songId + "-preview-60s.mp3";
        if (!storage.exists(objectKey)) {
            throw new BusinessException(ErrorCode.SONG_PREVIEW_NOT_READY);
        }
        return new SongPreviewResponse(
                songId,
                60,
                storage.createDownloadUrl(objectKey, properties.downloadUrlTtl()).toString(),
                clock.instant().plus(properties.downloadUrlTtl()));
    }
}
