package com.micon.backend.voice.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public interface VoiceStorage {
    void upload(String objectKey, InputStream content, long contentLength, String contentType);
    StoredObject download(String objectKey);
    URI createDownloadUrl(String objectKey, Duration validity);
    void delete(String objectKey);

    record StoredObject(byte[] content, String contentType) {
        public StoredObject {
            if (content == null) {
                throw new IllegalArgumentException("content is required");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
