package com.micon.backend.song.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SongPreviewPropertiesEncodingTest {

    @Test
    void previewObjectPrefixDefaultIsDecodedCorrectly() throws IOException {
        Properties properties = new Properties();

        Path mainProperties = Path.of("src", "main", "resources", "application.properties");
        assertThat(mainProperties).exists();

        try (InputStream input = Files.newInputStream(mainProperties)) {
            properties.load(input);
        }

        assertThat(properties.getProperty("song.preview.object-prefix"))
                .isEqualTo("${SONG_PREVIEW_OBJECT_PREFIX:catalog/정다운/source-preview-pilot-20260922/}");
    }
}
