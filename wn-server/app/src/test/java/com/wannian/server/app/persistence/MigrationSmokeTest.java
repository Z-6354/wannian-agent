package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K02 1B-1：空库 migration 与 SQLite PRAGMA 冒烟。
 *
 * <p>使用独立临时目录，不触碰开发者真实 {@code WANNIAN_DATA_DIR}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationSmokeTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void emptyDatabaseMigratesAndEnablesRequiredPragmas() throws Exception {
        Path dbFile = tempDataDir.resolve("wannian.db");
        assertThat(Files.isRegularFile(dbFile)).isTrue();
        assertThat(dbFile.toAbsolutePath().toString().toLowerCase(Locale.ROOT))
                .doesNotContain("target");

        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryPragma(connection, "foreign_keys")).isEqualTo("1");
            assertThat(queryPragma(connection, "journal_mode").toLowerCase(Locale.ROOT))
                    .isEqualTo("wal");

            Set<String> tables = listUserTables(connection);
            assertThat(tables)
                    .contains(
                            "conversation",
                            "message",
                            "turn",
                            "outbox_event",
                            "sequence_counter",
                            "turn_commit_plan")
                    .doesNotContain("turn_step", "memory_record", "background_task");
            assertThat(planDetails(connection, "SELECT sequence_no FROM message WHERE conversation_id = 'x' ORDER BY sequence_no"))
                    .doesNotContain("idx_message_conversation_seq");
            assertThat(planDetails(connection, "SELECT sequence_no FROM outbox_event ORDER BY sequence_no"))
                    .doesNotContain("idx_outbox_sequence");
        }
    }

    private static String queryPragma(Connection connection, String name) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery("PRAGMA " + name)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static Set<String> listUserTables(Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs =
                connection
                        .createStatement()
                        .executeQuery(
                                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static String planDetails(Connection connection, String sql) throws Exception {
        StringBuilder details = new StringBuilder();
        try (ResultSet rs = connection.createStatement().executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                details.append(rs.getString("detail")).append('\n');
            }
        }
        return details.toString();
    }
}
