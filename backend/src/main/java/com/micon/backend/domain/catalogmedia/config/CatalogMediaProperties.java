package com.micon.backend.domain.catalogmedia.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.media")
public record CatalogMediaProperties(String allowedPrefix, Duration downloadUrlTtl) {

    public CatalogMediaProperties {
        allowedPrefix = allowedPrefix == null || allowedPrefix.isBlank()
                ? "catalog/정영주/section-384-v1/vocals/"
                : allowedPrefix;
        downloadUrlTtl = downloadUrlTtl == null ? Duration.ofMinutes(10) : downloadUrlTtl;
        if (!allowedPrefix.startsWith("catalog/") || !allowedPrefix.endsWith("/")
                || allowedPrefix.contains("..") || allowedPrefix.contains("\\")) {
            throw new IllegalArgumentException("catalog.media.allowed-prefix must be a safe catalog prefix");
        }
        if (downloadUrlTtl.isZero() || downloadUrlTtl.isNegative()
                || downloadUrlTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("catalog.media.download-url-ttl must be between 1 ms and 1 hour");
        }
    }
}
