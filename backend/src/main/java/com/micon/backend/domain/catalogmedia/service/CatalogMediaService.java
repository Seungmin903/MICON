package com.micon.backend.domain.catalogmedia.service;

import com.micon.backend.domain.catalogmedia.config.CatalogMediaProperties;
import com.micon.backend.domain.catalogmedia.dto.CatalogMediaResponse;
import com.micon.backend.domain.catalogmedia.repository.CatalogMediaRepository;

import com.micon.backend.domain.catalogmedia.repository.CatalogMediaRepository.VocalStemRow;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogMediaService {

    private final CatalogMediaRepository repository;
    private final VoiceStorage storage;
    private final CatalogMediaProperties properties;
    private final Clock clock;

    public CatalogMediaService(CatalogMediaRepository repository, VoiceStorage storage,
                               CatalogMediaProperties properties, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogMediaResponse vocalStem(String songId) {
        if (!repository.songExists(songId)) {
            throw new BusinessException(ErrorCode.SONG_NOT_FOUND);
        }
        VocalStemRow media = repository.findReadyVocalStem(songId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATALOG_MEDIA_NOT_READY));
        String expectedSuffix = media.catalogTrackId() + "/vocals.wav";
        if (media.catalogTrackId() == null || media.catalogTrackId().isBlank()
                || media.objectKey() == null
                || !media.objectKey().startsWith(properties.allowedPrefix())
                || !media.objectKey().equals(properties.allowedPrefix() + expectedSuffix)
                || !"audio/wav".equals(media.mimeType())
                || media.fileBytes() == null || media.fileBytes() <= 0
                || media.sha256() == null || !media.sha256().matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.CATALOG_MEDIA_NOT_READY);
        }
        String url = storage.createDownloadUrl(media.objectKey(), properties.downloadUrlTtl()).toString();
        return new CatalogMediaResponse(
                media.mediaId(), media.songId(), media.catalogTrackId(), "VOCAL_STEM",
                media.objectKey(), media.mimeType(), media.fileBytes(), media.sha256(), url,
                clock.instant().plus(properties.downloadUrlTtl()));
    }
}
