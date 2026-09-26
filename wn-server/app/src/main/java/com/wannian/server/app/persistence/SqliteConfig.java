package com.wannian.server.app.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import com.wannian.server.kernel.conversation.ConversationTitlePolicy;
import com.wannian.server.kernel.conversation.SequentialConversationTitlePolicy;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * K02 1B-1：本机 SQLite DataSource。
 *
 * <p>职责：根据 {@code wannian.data-dir} 创建目录与 {@code wannian.db}，并启用文档要求的
 * foreign_keys / WAL / busy_timeout。Flyway 使用本 Bean 跑 {@code db/migration}。
 *
 * <p>相对路径默认落到 {@code wn-server/data}：若启动 cwd 为 {@code app} 子模块，则解析到父目录下的
 * {@code data/}，避免写成 {@code wn-server/app/data}。
 *
 * <p>不做业务写入。一致性备份在 {@link SqliteConsistencyBackup}，不在本类复制库文件。
 * 换 MySQL 时替换本配置与 migration，而非只改 URL。
 */
@Configuration
public class SqliteConfig {

    @Bean
    ConversationTitlePolicy conversationTitlePolicy() {
        return new SequentialConversationTitlePolicy();
    }

    /**
     * 组装单文件 SQLite DataSource。
     *
     * <p>busy 等待毫秒来自 {@code wannian.sqlite.busy-timeout-ms}，禁止在代码写死业务默认。
     *
     * @param dataDir {@code wannian.data-dir}，须在 target/ 之外
     * @return 已配置 PRAGMA 的 DataSource
     */
    @Bean
    DataSource dataSource(
            @Value("${wannian.data-dir}") String dataDir,
            @Value("${wannian.sqlite.busy-timeout-ms:5000}") int busyTimeoutMs)
            throws IOException {
        if (busyTimeoutMs < 1) {
            throw new IllegalArgumentException("wannian.sqlite.busy-timeout-ms 须 ≥ 1");
        }
        Path dir = resolveDataDir(dataDir);
        assertNotUnderTarget(dir);
        Files.createDirectories(dir);
        Path dbFile = dir.resolve("wannian.db");

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setBusyTimeout(busyTimeoutMs);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqliteConfig);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

    /**
     * 绝对路径原样使用；相对路径相对进程 cwd，若 cwd 为 app 模块则提到 wn-server 根。
     */
    public static Path resolveDataDir(String dataDir) {
        Path configured = Path.of(dataDir);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path base = cwd;
        Path leaf = cwd.getFileName();
        if (leaf != null
                && "app".equals(leaf.toString())
                && Files.isRegularFile(cwd.resolve("pom.xml"))
                && cwd.getParent() != null) {
            base = cwd.getParent();
        }
        return base.resolve(configured).normalize();
    }

    /** 禁止把库放到 Maven {@code target/}，避免 clean 丢数据。 */
    private static void assertNotUnderTarget(Path dir) {
        for (Path part : dir.normalize()) {
            if ("target".equalsIgnoreCase(part.toString())) {
                throw new IllegalStateException("wannian.data-dir must not be under target/: " + dir);
            }
        }
    }
}
