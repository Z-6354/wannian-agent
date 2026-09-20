package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * V002 曾把 outbox 计数器写成 1。V003 必须从已有序号继续，并且不能把已经更大的计数器调小。
 */
class OutboxSequenceUpgradeTest {

    @Test
    void existingV001EventsKeepLaterSequences(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("wannian.db"));
        migrate(dataSource, "1");

        String now = "2026-09-18T12:00:00Z";
        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        TurnId turnId = TurnId.generate();
        try (Connection connection = dataSource.getConnection()) {
            insertConversation(connection, conversationId, now, false);
            insertUserMessage(connection, conversationId, messageId, now);
            insertRunningTurn(connection, turnId, conversationId, messageId, now);
            insertOutbox(connection, UUID.randomUUID().toString(), turnId.asString(), 1L, now);
            insertOutbox(connection, UUID.randomUUID().toString(), turnId.asString(), 5L, now);
        }

        migrate(dataSource, null);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(counter(connection)).isEqualTo(6L);
        }

        MessageId assistantId = MessageId.generate();
        Instant freezeAt = Instant.parse("2026-09-18T12:00:00Z");
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"升级\"}", 0);
        SqliteTurnCommitter committer = new SqliteTurnCommitter(dataSource, new ObjectMapper());
        FreezeCommitResult frozen =
                committer.freezeCommit(FreezeCommitPlan.of(turnId, 1L, "exec-seed", freezeAt, assistant));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        CommitTurnResult result =
                committer.commit(
                        CommitTurnPlan.completeTurn(
                                turnId,
                                ((FreezeCommitResult.Frozen) frozen).committingRevision(),
                                "exec-seed",
                                assistant,
                                List.of()));
        assertThat(result).isInstanceOf(CommitTurnResult.Committed.class);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            long sequence =
                    scalarLong(
                            connection,
                            """
                            SELECT sequence_no FROM outbox_event
                            WHERE event_type = 'TurnCompleted' AND aggregate_id = ?
                            ORDER BY sequence_no DESC
                            LIMIT 1
                            """,
                            turnId.asString());
            assertThat(sequence).isGreaterThan(5L);
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            """
                            SELECT COUNT(*) FROM outbox_event
                            WHERE sequence_no > 5 AND aggregate_id = ?
                            """)) {
                ps.setString(1, turnId.asString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void emptyDatabaseStillStartsAtOne(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("wannian.db"));
        migrate(dataSource, null);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(counter(connection)).isEqualTo(1L);
        }

        String now = "2026-09-18T12:00:00Z";
        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        TurnId turnId = TurnId.generate();
        try (Connection connection = dataSource.getConnection()) {
            insertConversation(connection, conversationId, now, true);
            insertUserMessage(connection, conversationId, messageId, now);
            insertRunningTurn(connection, turnId, conversationId, messageId, now);
        }

        SqliteTurnCommitter committer = new SqliteTurnCommitter(dataSource, new ObjectMapper());
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"空库\"}", 0);
        FreezeCommitResult frozen =
                committer.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId, 1L, "exec-seed", Instant.parse("2026-09-18T12:00:00Z"), assistant));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        assertThat(
                        committer.commit(
                                CommitTurnPlan.completeTurn(
                                        turnId,
                                        ((FreezeCommitResult.Frozen) frozen).committingRevision(),
                                        "exec-seed",
                                        assistant,
                                        List.of())))
                .isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(
                            scalarLong(
                                    connection,
                                    "SELECT sequence_no FROM outbox_event WHERE aggregate_id = ?",
                                    turnId.asString()))
                    .isEqualTo(1L);
        }
    }

    @Test
    void backfillDoesNotLowerACounterAlreadyAhead(@TempDir Path dir) throws Exception {
        SQLiteDataSource dataSource = dataSource(dir.resolve("wannian.db"));
        migrate(dataSource, "2");
        try (Connection connection = dataSource.getConnection()) {
            insertOutbox(connection, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 4L, "2026-09-18T12:00:00Z");
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "UPDATE sequence_counter SET next_value = 20 WHERE name = 'outbox'")) {
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }

        migrate(dataSource, null);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(counter(connection)).isEqualTo(20L);
        }
    }

    private static SQLiteDataSource dataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return dataSource;
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration =
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void insertConversation(
            Connection connection, String id, String now, boolean withSequence) throws Exception {
        String sql =
                withSequence
                        ? """
                        INSERT INTO conversation (
                            id, title, status, revision, created_at, updated_at, next_message_seq
                        ) VALUES (?, NULL, 'ACTIVE', 1, ?, ?, 2)
                        """
                        : """
                        INSERT INTO conversation (id, title, status, revision, created_at, updated_at)
                        VALUES (?, NULL, 'ACTIVE', 1, ?, ?)
                        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static void insertUserMessage(Connection connection, String conversationId, String messageId, String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO message (
                            id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                        ) VALUES (?, ?, NULL, 'USER', '{"v":1,"text":"hi"}', 1, ?)
                        """)) {
            ps.setString(1, messageId);
            ps.setString(2, conversationId);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static void insertRunningTurn(
            Connection connection, TurnId turnId, String conversationId, String messageId, String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO turn (
                            id, conversation_id, client_request_id, status, input_message_id,
                            output_message_id, execution_id, claim_expires_at, revision, error_code,
                            created_at, updated_at, completed_at
                        ) VALUES (?, ?, ?, 'RUNNING', ?, NULL, 'exec-seed', ?, 1, NULL, ?, ?, NULL)
                        """)) {
            ps.setString(1, turnId.asString());
            ps.setString(2, conversationId);
            ps.setString(3, "req-" + turnId.asString());
            ps.setString(4, messageId);
            ps.setString(5, Instant.parse(now).plusSeconds(60).toString());
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private static void insertOutbox(
            Connection connection, String eventId, String aggregateId, long sequenceNo, String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO outbox_event (
                            id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                        ) VALUES (?, 'turn', ?, 'TurnCompleted', '{}', ?, ?)
                        """)) {
            ps.setString(1, eventId);
            ps.setString(2, aggregateId);
            ps.setLong(3, sequenceNo);
            ps.setString(4, now);
            ps.executeUpdate();
        }
    }

    private static long counter(Connection connection) throws Exception {
        try (ResultSet rs =
                connection
                        .createStatement()
                        .executeQuery("SELECT next_value FROM sequence_counter WHERE name = 'outbox'")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static String scalar(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static long scalarLong(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
