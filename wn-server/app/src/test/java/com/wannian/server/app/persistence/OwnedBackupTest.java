package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 备份 manifest、流式摘要和空 history 拒绝，不启动 Spring。 */
class OwnedBackupTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void manifestRoundTripsQuotesSlashNewlineAndChinese() throws Exception {
        String buildId = "a\"b\\c\n中文";
        String digest = "ab".repeat(32);
        String json =
                OwnedBackup.manifest(
                        objectMapper, Instant.parse("2026-09-18T00:00:00Z"), "002", buildId, digest);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(node.get("source").asText()).isEqualTo(OwnedBackup.SOURCE);
        assertThat(node.get("status").asText()).isEqualTo("complete");
        assertThat(node.get("applicationBuildId").asText()).isEqualTo(buildId);
        assertThat(node.get("databaseDigest").asText()).isEqualTo(digest);
    }

    @Test
    void manifestRoundTripsControlCharacter() throws Exception {
        String buildId = "build\u0001id";
        String json =
                OwnedBackup.manifest(
                        objectMapper,
                        Instant.parse("2026-09-18T00:00:00Z"),
                        "004",
                        buildId,
                        "ab".repeat(32));
        assertThat(objectMapper.readTree(json).get("applicationBuildId").asText()).isEqualTo(buildId);
    }

    @Test
    void streamingDigestMatchesFullReadPastTheBuffer() throws Exception {
        Path file = Files.createTempFile("wn-digest", ".bin");
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Files.write(file, payload);
        String streamed = OwnedBackup.sha256(file);
        String full = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        assertThat(streamed).isEqualTo(full);
    }

    @Test
    void manualDirectoryIsNotPrunable(@TempDir Path root) throws Exception {
        Path backups = root.resolve("backups");
        Path manual = backups.resolve("000-manual");
        Files.createDirectories(manual);
        Files.writeString(manual.resolve("wannian.db"), "x");
        Files.writeString(manual.resolve("wannian-backup.json"), "{\"source\":\"hand\"}");
        Files.writeString(manual.resolve("notes.txt"), "keep");
        assertThat(OwnedBackup.isPrunable(backups, manual, objectMapper)).isFalse();
    }

    @Test
    void emptyHistoryRefusesSnapshot(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("wannian.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER, version TEXT)");
        }
        SqliteConsistencyBackup backup =
                new SqliteConsistencyBackup(dataSource, objectMapper, dir.toString(), "build-id");
        assertThatThrownBy(() -> backup.createSnapshot(dir.resolve("snap")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未 migration");
    }

    @Test
    void blankSchemaVersionRefusesSnapshot(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("wannian.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            connection.createStatement()
                    .execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER, version TEXT)");
            connection.createStatement().execute("INSERT INTO flyway_schema_history (installed_rank, version) VALUES (1, '')");
        }
        SqliteConsistencyBackup backup =
                new SqliteConsistencyBackup(dataSource, objectMapper, dir.toString(), "build-id");
        assertThatThrownBy(() -> backup.createSnapshot(dir.resolve("snap")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 为空");
    }

    @Test
    void incompleteSnapshotIsNotPrunable(@TempDir Path root) throws Exception {
        Path backups = root.resolve("backups");
        Path snap = backups.resolve("wn-snap-20260918000000000");
        Files.createDirectories(snap);
        Files.writeString(snap.resolve("wannian.db"), "partial");
        assertThat(OwnedBackup.isPrunable(backups, snap, objectMapper)).isFalse();
        assertThat(snap.resolve("wannian.db")).isRegularFile();
    }

    @Test
    void junctionOutsideBackupRootIsNotPrunable(@TempDir Path root) throws Exception {
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve(OwnedBackup.DATABASE_FILE), "secret");
        Files.writeString(
                outside.resolve(OwnedBackup.MANIFEST_FILE),
                OwnedBackup.manifest(
                        objectMapper,
                        Instant.parse("2026-09-18T00:00:00Z"),
                        "004",
                        "build-id",
                        "ab".repeat(32)));
        Path backups = root.resolve("backups");
        Files.createDirectories(backups);
        Path link = backups.resolve("wn-snap-20260918000000001");
        Process process =
                new ProcessBuilder(
                                "cmd.exe",
                                "/c",
                                "mklink /J \"" + link + "\" \"" + outside + "\"")
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(OwnedBackup.isPrunable(backups, link, objectMapper)).isFalse();
        assertThat(outside.resolve(OwnedBackup.DATABASE_FILE)).isRegularFile();
    }

    @Test
    void backupFailureDoesNotMigrateOrDropExistingSnapshot(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("wannian.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            connection.createStatement()
                    .execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER, version TEXT)");
            connection.createStatement()
                    .execute("INSERT INTO flyway_schema_history (installed_rank, version) VALUES (1, '004')");
        }
        Path existing = dir.resolve("backups").resolve("wn-snap-20260918000000000");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("wannian.db"), "keep");
        com.wannian.server.kernel.backup.ConsistencyBackup failing =
                target -> {
                    throw new IllegalStateException("校验失败");
                };
        BackupBeforeMigrate migrate =
                new BackupBeforeMigrate(dataSource, failing, objectMapper, dir.toString(), 1);
        boolean[] migrated = {false};
        assertThatThrownBy(() -> migrate.migrateGuarded(true, true, () -> migrated[0] = true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("校验失败");
        assertThat(migrated[0]).isFalse();
        assertThat(existing.resolve("wannian.db")).isRegularFile();
    }

    @Test
    void firstStartWithoutHistoryStillMigrates(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("wannian.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE placeholder (id INTEGER)");
        }
        com.wannian.server.kernel.backup.ConsistencyBackup unused =
                target -> {
                    throw new IllegalStateException("不应备份");
                };
        BackupBeforeMigrate migrate =
                new BackupBeforeMigrate(dataSource, unused, objectMapper, dir.toString(), 1);
        boolean[] migrated = {false};
        migrate.migrateGuarded(true, false, () -> migrated[0] = true);
        assertThat(migrated[0]).isTrue();
    }
}
