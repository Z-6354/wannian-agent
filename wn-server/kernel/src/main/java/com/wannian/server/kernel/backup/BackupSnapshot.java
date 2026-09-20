package com.wannian.server.kernel.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次一致性备份的元数据。字段与 29 号文档第 13 节一致。
 *
 * @param createdAt 备份完成时刻
 * @param schemaVersion 当时的 migration 版本（Flyway {@code version}）
 * @param applicationBuildId 构建标识；未接入构建流水线时为 {@code unknown}
 * @param databaseDigest 快照文件的 SHA-256（小写十六进制），不含旁路 JSON
 * @param backupFile 可单独打开的快照库路径
 */
public record BackupSnapshot(
        Instant createdAt,
        String schemaVersion,
        String applicationBuildId,
        String databaseDigest,
        Path backupFile) {

    public BackupSnapshot {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(applicationBuildId, "applicationBuildId");
        Objects.requireNonNull(databaseDigest, "databaseDigest");
        Objects.requireNonNull(backupFile, "backupFile");
        if (schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion 不能为空");
        }
        if (applicationBuildId.isBlank()) {
            throw new IllegalArgumentException("applicationBuildId 不能为空");
        }
        if (!databaseDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("databaseDigest 必须是 SHA-256 小写十六进制");
        }
    }
}
