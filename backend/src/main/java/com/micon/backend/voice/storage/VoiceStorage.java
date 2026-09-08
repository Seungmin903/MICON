package com.micon.backend.voice.storage;

import java.io.InputStream;

public interface VoiceStorage {
    void upload(String objectKey, InputStream content, long contentLength, String contentType);
    void delete(String objectKey);
}
