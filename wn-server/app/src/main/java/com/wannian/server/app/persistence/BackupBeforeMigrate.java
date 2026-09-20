package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.backup.ConsistencyBackup;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * 已有 schema 且 Flyway 还有待执行 migration 时，先做一致性备份，成功后再迁移，最后才淘汰旧快照。
 *
 * <p>没有 pending migration 的普通重启不新建备份，也不删旧备份，避免每次启动消耗恢复历史。
 * 备份失败或 migration 抛错时不淘汰。空库第一次启动没有 {@code flyway_schema_history}，跳过备份。
 * 自动快照目录名以 {@code wn-snap-} 开头，并在数据库、摘要和 manifest 都写完后才标 {@code complete}。
 */
@Component
public class BackupBeforeMigrate implements FlywayMigrationStrategy {

    private static final DateTimeFormatter DIRECTORY_NAME =
            DateTimeFormatter.ofPattern("'wn-snap-'yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final DataSource dataSource;
    private final ConsistencyBackup consistencyBackup;
    private final ObjectMapper objectMapper;
    private final String dataDir;
    private final int retainCount;

    public BackupBeforeMigrate(
            DataSource dataSource,
            ConsistencyBackup consistencyBackup,
            ObjectMapper objectMapper,
            @Value("${wannian.data-dir}") String dataDir,
            @Value("${wannian.backup.retain-count}") int retainCount) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.consistencyBackup = Objects.requireNonNull(consistencyBackup, "consistencyBackup");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        if (retainCount < 1) {
            throw new IllegalArgumentException("wannian.backup.retain-count 至少为 1，实际为 " + retainCount);
        }
        this.retainCount = retainCount;
    }

    @Override
    public void migrate(Flyway flyway) {
        boolean historyExists;
        try {
            historyExists = schemaHistoryExists();
        } catch (SQLException ex) {
            throw new IllegalStateException("无法检查 flyway_schema_history，已中止迁移", ex);
        }
        migrateGuarded(flyway.info().pending().length > 0, historyExists, flyway::migrate);
    }

    /**
     * 有待执行 migration 且历史表已存在时才备份。迁移抛错则不淘汰。
     * 无 pending 时不备份也不淘汰。
     */
    void migrateGuarded(boolean pending, boolean historyExists, Runnable migrate) {
        if (pending && historyExists) {
            backupExistingSchema();
        }
        migrate.run();
        if (pending) {
            try {
                pruneOwnedSnapshots();
            } catch (IOException ex) {
                throw new IllegalStateException("migration 已完成，但清理旧自动快照失败", ex);
            }
        }
    }

    /** 已 migration 过的库创建一份新快照。不在这里淘汰旧目录。 */
    void backupExistingSchema() {
        try {
            if (!schemaHistoryExists()) {
                return;
            }
            Path directory = nextBackupDirectory();
            Files.createDirectories(directory);
            consistencyBackup.createSnapshot(directory);
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("migration 前一致性备份失败，已中止迁移", ex);
        }
    }

    private boolean schemaHistoryExists() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                """
                                SELECT 1 FROM sqlite_master
                                WHERE type = 'table' AND name = 'flyway_schema_history'
                                """)) {
            return rs.next();
        }
    }

    private Path nextBackupDirectory() {
        Path backups = SqliteConfig.resolveDataDir(dataDir).resolve("backups");
        String stamp = DIRECTORY_NAME.format(Instant.now());
        Path directory = backups.resolve(stamp);
        int suffix = 2;
        while (Files.exists(directory)) {
            directory = backups.resolve(stamp + "-" + suffix);
            suffix++;
        }
        return directory;
    }

    /** 只删除超出保留份数、且同时满足命名、manifest、数据库和成功标记的自动快照。 */
    void pruneOwnedSnapshots() throws IOException {
        Path backups = backupsRoot();
        if (!Files.isDirectory(backups)) {
            return;
        }
        List<Path> snapshots = new ArrayList<>();
        try (var listed = Files.list(backups)) {
            for (Path path : listed.filter(Files::isDirectory).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                if (OwnedBackup.isPrunable(backups, path, objectMapper)) {
                    snapshots.add(path);
                }
            }
        }
        int excess = snapshots.size() - retainCount;
        for (int i = 0; i < excess; i++) {
            deleteRecursively(snapshots.get(i));
        }
    }

    private Path backupsRoot() {
        return SqliteConfig.resolveDataDir(dataDir).resolve("backups");
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                            throw new IOException("拒绝跟随符号链接删除: " + file);
                        }
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                            throw new IOException("拒绝删除符号链接目录: " + dir);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
