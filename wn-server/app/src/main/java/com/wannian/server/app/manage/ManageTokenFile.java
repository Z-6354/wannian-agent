package com.wannian.server.app.manage;

import com.wannian.server.app.persistence.SqliteConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 外网管理口令。环境变量优先；否则读数据目录里的 {@code manage.properties}，没有就写入默认值。
 * 口令不进日志。
 */
@Component
public class ManageTokenFile {

    static final String FILE_NAME = "manage.properties";
    static final String DEFAULT_TOKEN = "dev-manage";

    private final String token;

    public ManageTokenFile(
            @Value("${wannian.manage.token:}") String configured, @Value("${wannian.data-dir:data}") String dataDir)
            throws IOException {
        if (configured != null && !configured.isBlank()) {
            this.token = configured;
            return;
        }
        this.token = loadOrCreate(SqliteConfig.resolveDataDir(dataDir));
    }

    public String token() {
        return token;
    }

    static String loadOrCreate(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            String existing = readToken(file);
            if (existing != null && !existing.isBlank()) {
                return existing;
            }
        }
        Files.writeString(
                file,
                """
                # 外网访问 /api/manage 时的口令。本机回环地址不校验。
                token=%s
                """
                        .formatted(DEFAULT_TOKEN),
                StandardCharsets.UTF_8);
        return DEFAULT_TOKEN;
    }

    static String readToken(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("token=")) {
                return trimmed.substring("token=".length()).trim();
            }
        }
        return "";
    }
}
