package com.micon.backend.domain.song.preview;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3에 일괄 업로드된 60초 원곡 미리듣기의 읽기 전용 위치다. */
@ConfigurationProperties(prefix = "song.preview")
public record SongPreviewProperties(String objectPrefix, Duration downloadUrlTtl) {

    public SongPreviewProperties {
        objectPrefix = objectPrefix == null || objectPrefix.isBlank()
                ? "catalog/정다운/source-preview-pilot-20260922/" : objectPrefix;
        downloadUrlTtl = downloadUrlTtl == null ? Duration.ofMinutes(10) : downloadUrlTtl;
        if (!objectPrefix.startsWith("catalog/") || !objectPrefix.endsWith("/")
                || objectPrefix.contains("..") || objectPrefix.contains("\\")) {
            throw new IllegalArgumentException("song.preview.object-prefix must be a safe catalog prefix");
        }
        if (downloadUrlTtl.isZero() || downloadUrlTtl.isNegative()
                || downloadUrlTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("song.preview.download-url-ttl must be between 1 ms and 1 hour");
        }
    }
}
