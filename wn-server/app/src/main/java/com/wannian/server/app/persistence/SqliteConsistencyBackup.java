package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.backup.BackupSnapshot;
import com.wannian.server.kernel.backup.ConsistencyBackup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.sqlite.SQLiteConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ConsistencyBackup} 的 SQLite 实现：调用驱动的 backup API，不复制主文件或 WAL。
 *
 * <p>快照旁写入 {@code wannian-backup.json}。摘要只覆盖快照库文件本身。
 */
@Component
public class SqliteConsistencyBackup implements ConsistencyBackup {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String dataDir;
    private final String applicationBuildId;

    public SqliteConsistencyBackup(
            DataSource dataSource,
            ObjectMapper objectMapper,
            @Value("${wannian.data-dir}") String dataDir,
            @Value("${wannian.build-id}") String applicationBuildId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.applicationBuildId = Objects.requireNonNull(applicationBuildId, "applicationBuildId");
        if (applicationBuildId.isBlank()) {
            throw new IllegalArgumentException("wannian.build-id 不能为空白");
        }
    }

    @Override
    public BackupSnapshot createSnapshot(Path targetDirectory) {
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Path directory = targetDirectory.toAbsolutePath().normalize();
        Path snapshotFile = directory.resolve(OwnedBackup.DATABASE_FILE);
        Path liveFile = SqliteConfig.resolveDataDir(dataDir).resolve(OwnedBackup.DATABASE_FILE).toAbsolutePath().normalize();
        if (snapshotFile.equals(liveFile)) {
            throw new IllegalArgumentException("禁止把快照写回正在使用的库文件: " + liveFile);
        }
        if (Files.exists(snapshotFile)) {
            throw new IllegalArgumentException("快照文件已存在: " + snapshotFile);
        }

        try {
            Files.createDirectories(directory);
            Instant createdAt = Instant.now();
            String schemaVersion = exportSnapshot(snapshotFile);
            String digest = OwnedBackup.sha256(snapshotFile);
            Files.writeString(
                    directory.resolve(OwnedBackup.MANIFEST_FILE),
                    OwnedBackup.manifest(
                            objectMapper, createdAt, schemaVersion, applicationBuildId, digest));
            return new BackupSnapshot(createdAt, schemaVersion, applicationBuildId, digest, snapshotFile);
        } catch (IOException ex) {
            throw new IllegalStateException("写入一致性快照失败: " + snapshotFile, ex);
        }
    }

    private String exportSnapshot(Path snapshotFile) {
        try (Connection connection = dataSource.getConnection()) {
            String schemaVersion = readSchemaVersion(connection);
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int rc = sqlite.getDatabase().backup("main", snapshotFile.toString(), (remaining, pageCount) -> {});
            if (rc != 0) {
                throw new IllegalStateException("SQLite backup 返回错误码 " + rc + ": " + snapshotFile);
            }
            return schemaVersion;
        } catch (SQLException ex) {
            throw new IllegalStateException("导出一致性快照失败: " + snapshotFile, ex);
        }
    }

    private static String readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                """
                                SELECT version FROM flyway_schema_history
                                ORDER BY installed_rank DESC
                                LIMIT 1
                                """)) {
            if (!rs.next()) {
                throw new IllegalStateException("尚未 migration，不能做一致性备份");
            }
            String version = rs.getString(1);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("flyway_schema_history.version 为空");
            }
            return version;
        }
    }
}
