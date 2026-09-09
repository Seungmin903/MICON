package com.micon.verification;

import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Destructive test data is allowed only in a NEW disposable local/service DB. */
public final class SongImportVerifier {
    private SongImportVerifier() {}

    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("MICON_MYSQL_TEST"))) {
            throw new IllegalStateException("Set MICON_MYSQL_TEST=1 only for disposable MySQL.");
        }
        String host = System.getenv().getOrDefault("MICON_TEST_DB_HOST", "mysql");
        if (!Set.of("mysql", "localhost", "127.0.0.1").contains(host)) {
            throw new IllegalArgumentException("Only local/test-service MySQL hosts are allowed.");
        }
        String password = System.getenv("MICON_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("MICON_TEST_DB_PASSWORD is required.");
        }
        // allowMultiQueries is confined to this test harness for trusted repository SQL.
        String url = "jdbc:mysql://" + host + ":3306/micon"
                + "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8"
                + "&allowMultiQueries=true&connectTimeout=2000&socketTimeout=60000";
        try (Connection db = awaitDatabase(url, password)) {
            requireEqual("empty test schema", 0, scalar(db,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            if (!query(db, "SELECT @@sql_mode").getFirst().contains("STRICT_TRANS_TABLES")) {
                throw new IllegalStateException("Strict SQL mode must be enabled for date validation.");
            }
            Flyway flyway = Flyway.configure()
                    .dataSource(url, "root", password)
                    .locations("filesystem:backend/src/main/resources/db/migration")
                    .validateMigrationNaming(true)
                    .cleanDisabled(true)
                    .load();
            flyway.migrate();
            flyway.validate();
            requireEqual("no pending migrations", 0, flyway.info().pending().length);
            requireEqual("second migrate is a no-op", 0, flyway.migrate().migrationsExecuted);

            Map<String, List<String>> firstIdentity = null;
            long firstSingers = -1;
            for (int pass = 1; pass <= 2; pass++) {
                executeFile(db, Path.of("docs/genre_seed.sql"));
                executeFile(db, Path.of("docs/section_metadata_insert.sql"));
                assertCatalog(db);
                Map<String, List<String>> identity = Map.of(
                        "songs", query(db, "SELECT catalog_track_id, song_id FROM song_metadata ORDER BY catalog_track_id"),
                        "singers", query(db, "SELECT normalized_name, singer_id FROM singer ORDER BY normalized_name"),
                        "analyses", query(db, "SELECT song_id, analysis_id FROM song_analysis ORDER BY song_id"),
                        "sections", query(db, "SELECT song_id, sequence_no, section_id FROM song_section ORDER BY song_id, sequence_no"));
                long singers = scalar(db, "SELECT COUNT(*) FROM singer");
                if (firstIdentity == null) {
                    firstIdentity = identity;
                    firstSingers = singers;
                } else {
                    requireEqual("singer count after rerun", firstSingers, singers);
                    if (!firstIdentity.equals(identity)) {
                        throw new IllegalStateException("Catalog identifiers changed on the second import.");
                    }
                }
                System.out.printf("PASS import %d: songs=384 metadata=384 singers=%d genres=28 "
                        + "song_genre=221 analyses=384 sections=1968 media=0 notes=0%n", pass, singers);
            }
            flyway.validate();
            System.out.println("PASS Flyway + two imports: counts, IDs, joins and partial dates verified.");
        }
    }

    private static Connection awaitDatabase(String url, String password) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        SQLException last = null;
        do {
            try {
                return DriverManager.getConnection(url, "root", password);
            } catch (SQLException failure) {
                last = failure;
                Thread.sleep(1000);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Disposable MySQL was not ready within 90 seconds.", last);
    }

    private static void executeFile(Connection db, Path file) throws Exception {
        try (Statement statement = db.createStatement()) {
            statement.setQueryTimeout(120);
            // Let MySQL parse quoted lyrics/JSON; splitting on ';' corrupts the input.
            boolean result = statement.execute(Files.readString(file, StandardCharsets.UTF_8));
            while (result || statement.getUpdateCount() != -1) {
                result = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
        } catch (SQLException failure) {
            // MySQL DDL is not transactional; this DB is discarded after a failure.
            try (Statement rollback = db.createStatement()) {
                rollback.execute("ROLLBACK");
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Import failed: " + file, failure);
        }
    }

    private static void assertCatalog(Connection db) throws SQLException {
        Map<String, Long> expected = Map.of(
                "song", 384L, "song_metadata", 384L, "song_singer", 384L,
                "genre", 28L, "song_genre", 221L, "song_analysis", 384L,
                "song_section", 1968L, "song_media", 0L, "song_note", 0L);
        for (var table : expected.entrySet()) {
            requireEqual(table.getKey(), table.getValue(),
                    scalar(db, "SELECT COUNT(*) FROM " + table.getKey()));
        }
        requireEqual("unique catalog IDs", 384, scalar(db,
                "SELECT COUNT(DISTINCT catalog_track_id) FROM song_metadata"));
        requireEqual("pending metadata", 211, scalar(db,
                "SELECT COUNT(*) FROM song_metadata WHERE metadata_review_status='PENDING'"));
        requireEqual("metadata needing review", 173, scalar(db,
                "SELECT COUNT(*) FROM song_metadata WHERE metadata_review_status='NEEDS_REVIEW'"));
        requireEqual("partial analyses", 384, scalar(db,
                "SELECT COUNT(*) FROM song_analysis WHERE status='PARTIAL' AND timings IS NOT NULL"));
        requireEqual("section-analysis song ownership", 0, scalar(db,
                "SELECT COUNT(*) FROM song_section s LEFT JOIN song_analysis a ON a.analysis_id=s.analysis_id "
                        + "WHERE a.song_id IS NULL OR a.song_id<>s.song_id"));
        requireEqual("partial dates preserved", 2, scalar(db,
                "SELECT COUNT(*) FROM song_metadata WHERE release_date IS NULL AND "
                        + "((catalog_track_id='cd03ea02e49caaa5' AND release_date_raw='1969-02') OR "
                        + "(catalog_track_id='525a091f0bd0873a' AND release_date_raw='2008'))"));
        requireEqual("empty note/media inputs do not enable practice", 0, scalar(db,
                "SELECT COUNT(*) FROM song WHERE practice_ready=TRUE"));
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("Missing result for verification query.");
            }
            return rows.getLong(1);
        }
    }

    private static List<String> query(Connection db, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    row.add(rows.getString(column));
                }
                values.add(row.toString());
            }
        }
        return values;
    }

    private static void requireEqual(String label, long expected, long actual) {
        if (expected != actual) {
            throw new IllegalStateException(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
