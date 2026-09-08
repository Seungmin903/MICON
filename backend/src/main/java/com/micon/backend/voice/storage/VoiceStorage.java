package com.micon.backend.voice.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public interface VoiceStorage {
    void upload(String objectKey, InputStream content, long contentLength, String contentType);
    URI createDownloadUrl(String objectKey, Duration validity);
    void delete(String objectKey);
}
