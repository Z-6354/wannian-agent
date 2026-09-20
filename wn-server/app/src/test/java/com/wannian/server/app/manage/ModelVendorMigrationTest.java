package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 空库与停在 V004 的库都能迁到当前 vendor/listed 形态。 */
class ModelVendorMigrationTest {

    @Test
    void emptyDatabaseReachesVendorTables(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("empty.db"));
        migrate(dataSource, null);
        assertTables(dataSource);
    }

    @Test
    void databaseStoppedAtV004GainsVendorTables(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("upgraded.db"));
        migrate(dataSource, "4");
        migrate(dataSource, null);
        assertTables(dataSource);
    }

    @Test
    void enabledRowMigratesOntoListedFlag(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("enabled.db"));
        migrate(dataSource, "6");
        try (Connection connection = dataSource.getConnection()) {
            connection
                    .createStatement()
                    .execute(
                            """
                            INSERT INTO model_vendor (
                                id, display_name, protocol, base_url, api_key_env, revision, created_at, updated_at
                            ) VALUES (
                                'openai-main', '主', 'openai-compatible', 'https://example.test/v1',
                                'WANNIAN_MODEL_API_KEY', 0, 't', 't'
                            )
                            """);
            connection
                    .createStatement()
                    .execute(
                            """
                            INSERT INTO model_enabled (singleton, vendor_id, model_id, updated_at)
                            VALUES (1, 'openai-main', 'stub-chat', 't')
                            """);
        }
        migrate(dataSource, null);
        try (Connection connection = dataSource.getConnection();
                ResultSet tables =
                        connection
                                .createStatement()
                                .executeQuery(
                                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'model_enabled'");
                ResultSet listed =
                        connection
                                .createStatement()
                                .executeQuery(
                                        "SELECT model_id, enabled FROM model_listed WHERE vendor_id = 'openai-main'")) {
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isZero();
            assertThat(listed.next()).isTrue();
            assertThat(listed.getString(1)).isEqualTo("stub-chat");
            assertThat(listed.getInt(2)).isEqualTo(1);
        }
    }

    private static void assertTables(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery(
                                        """
                                        SELECT name FROM sqlite_master
                                        WHERE type = 'table' AND name IN ('model_vendor', 'model_enabled', 'model_listed')
                                        ORDER BY name
                                        """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("model_listed");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("model_vendor");
            assertThat(rs.next()).isFalse();
        }
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery("PRAGMA table_info(model_listed)")) {
            boolean hasEnabled = false;
            while (rs.next()) {
                if ("enabled".equals(rs.getString("name"))) {
                    hasEnabled = true;
                }
            }
            assertThat(hasEnabled).isTrue();
        }
    }

    private static SQLiteDataSource dataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return dataSource;
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
