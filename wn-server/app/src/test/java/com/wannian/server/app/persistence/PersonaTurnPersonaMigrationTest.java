package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class PersonaTurnPersonaMigrationTest {

    @TempDir Path tempDir;

    @Test
    void v023FailsClearlyAndPreservesRowsWithUnresolvedPersonaIds() throws Exception {
        Path database = tempDir.resolve("orphan-persona.db");
        createLegacyV022Database(database);

        assertThatThrownBy(() -> migrate(database))
                .hasMessageContaining("V023: turn_persona references a missing persona_definition");

        try (Connection connection = DriverManager.getConnection(jdbcUrl(database))) {
            assertThat(scalarLong(connection, "SELECT count(*) FROM turn_persona")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT count(*) FROM turn_persona WHERE persona_id = 'missing-persona'"))
                    .isEqualTo(1);
            assertThat(tableExists(connection, "turn_persona_v023")).isFalse();
        }
    }

    @Test
    void backupDatabaseCopyUpgradesFromV017ToV023WithoutChangingTurnCount() throws Exception {
        String configuredBackup = System.getProperty("wannian.migration.backup-db");
        assumeTrue(configuredBackup != null && !configuredBackup.isBlank(),
                "set -Dwannian.migration.backup-db to an offline database copy for upgrade verification");

        Path source = Path.of(configuredBackup).toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(source)).isTrue();
        Path copy = tempDir.resolve("backup-copy.db");
        Files.copy(source, copy);

        long originalTurnCount;
        try (Connection connection = DriverManager.getConnection(jdbcUrl(copy))) {
            originalTurnCount = scalarLong(connection, "SELECT count(*) FROM turn_persona");
        }

        Flyway flyway = migrate(copy);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("023");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(copy))) {
            assertThat(scalarLong(connection, "SELECT count(*) FROM turn_persona")).isEqualTo(originalTurnCount);
            assertThat(scalarLong(connection,
                    "SELECT count(*) FROM persona_definition WHERE id = 'yanhuo'")).isEqualTo(1);
            assertThat(hasForeignKey(connection, "turn_persona", "persona_id", "persona_definition", "id"))
                    .isTrue();
            assertThat(query(connection, "PRAGMA quick_check")).isEqualTo("ok");
            assertThat(hasRows(connection, "PRAGMA foreign_key_check")).isFalse();
        }
    }

    private static Flyway migrate(Path database) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(jdbcUrl(database));
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    private static void createLegacyV022Database(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database));
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE persona_definition (id TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL, display_name TEXT NOT NULL, profile_json TEXT NOT NULL, revision INTEGER NOT NULL, source_id TEXT NULL, request_key TEXT NULL UNIQUE, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE conversation (id TEXT PRIMARY KEY NOT NULL)");
            statement.execute("CREATE TABLE turn (id TEXT PRIMARY KEY NOT NULL)");
            statement.execute("CREATE TABLE turn_persona (turn_id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, persona_id TEXT NOT NULL, definition_revision INTEGER NOT NULL, binding_revision INTEGER NOT NULL, snapshot_json TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY (turn_id) REFERENCES turn(id), FOREIGN KEY (conversation_id) REFERENCES conversation(id))");
            statement.execute("INSERT INTO conversation(id) VALUES ('conversation-1')");
            statement.execute("INSERT INTO turn(id) VALUES ('turn-1')");
            statement.execute("INSERT INTO turn_persona VALUES ('turn-1', 'conversation-1', 'missing-persona', 1, 1, '{}', '2026-01-01T00:00:00Z')");
        }

        Flyway.configure()
                .dataSource(dataSource(database))
                .locations("classpath:db/migration")
                .baselineVersion("22")
                .baselineOnMigrate(true)
                .load()
                .baseline();
    }

    private static SQLiteDataSource dataSource(Path database) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(jdbcUrl(database));
        return dataSource;
    }

    private static String jdbcUrl(Path database) {
        return "jdbc:sqlite:" + database.toAbsolutePath().normalize();
    }

    private static boolean hasForeignKey(
            Connection connection, String table, String from, String referencedTable, String to)
            throws Exception {
        try (ResultSet rs = connection.createStatement()
                .executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (rs.next()) {
                if (from.equals(rs.getString("from"))
                        && referencedTable.equals(rs.getString("table"))
                        && to.equals(rs.getString("to"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static String query(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean hasRows(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            return rs.next();
        }
    }
}
