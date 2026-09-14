package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class VoiceProfileSchemaIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void replacesLegacyAnalysisTablesAndRelationships() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "VOICE_ANALYSIS_SESSION")).isTrue();
            assertThat(tableExists(connection, "USER_VOICE_PROFILE")).isTrue();
            assertThat(tableExists(connection, "VOICE_MODEL_RESULT")).isTrue();
            assertThat(tableExists(connection, "VOICE_ANALYSIS_JOB")).isTrue();
            assertThat(columnExists(connection, "VOICE_ANALYSIS_COMPONENT_JOB", "ANALYSIS_JOB_ID")).isTrue();
            assertThat(tableExists(connection, "VOICE_ANALYSIS")).isFalse();
            assertThat(tableExists(connection, "VOCAL_RANGE_ANALYSIS")).isFalse();
            assertThat(tableExists(connection, "VOCAL_TIMBRE_ANALYSIS")).isFalse();
            assertThat(columnExists(connection, "RECOMMENDATION", "PROFILE_ID")).isTrue();
            assertThat(columnExists(connection, "RECOMMENDATION", "VOICE_ANALYSIS_ID")).isFalse();
            assertThat(columnExists(connection, "PRACTICE_SESSION", "VOICE_ANALYSIS_ID")).isFalse();
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
            return rows.next();
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, column)) {
            return rows.next();
        }
    }
}
