package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class ReviewActivityWatermarkMigrationTest {

    @Test
    void upgradeFromV008DoesNotTreatMigrationTimeAsReadWatermark(@TempDir Path dir)
            throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("review.db").toAbsolutePath());
        migrate(dataSource, "8");

        String conversationId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String createdAt = "2026-01-01T00:00:00Z";
        String latestActivity = "2026-09-22T12:00:00Z";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "INSERT INTO conversation (id, title, status, revision, created_at, updated_at, last_activity_at) VALUES (?, NULL, 'ACTIVE', 1, ?, ?, ?)")) {
                ps.setString(1, conversationId);
                ps.setString(2, createdAt);
                ps.setString(3, latestActivity);
                ps.setString(4, latestActivity);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "INSERT INTO memory_review_job (id, conversation_id, companion_id, trigger, status, attempt, lease_owner, lease_until, last_error, created_at, updated_at) VALUES (?, ?, 'yanhuo', 'IDLE', 'SUCCEEDED', 1, NULL, NULL, NULL, ?, ?)")) {
                ps.setString(1, jobId);
                ps.setString(2, conversationId);
                ps.setString(3, createdAt);
                ps.setString(4, createdAt);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory_record (id, companion_identity_id, subject_key, content_kind, source_kind, scope, content_json, propose_id, triage_id, status, valid_from, revision, created_at, importance) "
                            + "VALUES (?, 'yanhuo', ?, 'USER_FACT', 'OBSERVED', 'COMPANION', '{\"v\":1,\"claim\":\"历史命题\"}', 'llm_review', 'validate', ?, ?, 1, ?, 0.8)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "pref.forgotten");
                ps.setString(3, "FORGOTTEN");
                ps.setString(4, createdAt);
                ps.setString(5, createdAt);
                ps.executeUpdate();
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "pref.active");
                ps.setString(3, "ACTIVE");
                ps.setString(4, createdAt);
                ps.setString(5, createdAt);
                ps.executeUpdate();
            }
        }

        migrate(dataSource, null);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT activity_watermark, reviewed_activity_watermark FROM memory_review_job WHERE id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("activity_watermark")).isNull();
                assertThat(rs.getString("reviewed_activity_watermark")).isNull();
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT generation FROM memory_subject_generation WHERE companion_identity_id = 'yanhuo' AND subject_key = ?")) {
            for (String subject : java.util.List.of("pref.forgotten", "pref.active")) {
                ps.setString(1, subject);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(1L);
                }
            }
        }
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration =
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
