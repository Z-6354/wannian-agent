package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnTerminalWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 2.4.4：COMMITTING 缺冻结计划时可 FAILED + Outbox，解除会话堵塞。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteTurnTerminalWriterCommittingFailTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TurnTerminalWriter terminalWriter;

    private ConversationId conversationId;
    private TurnId turnId;
    private MessageId messageId;

    @BeforeEach
    void seedCommittingWithoutPlan() throws Exception {
        conversationId = ConversationId.generate();
        turnId = TurnId.generate();
        messageId = MessageId.generate();
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation_search");
            connection.createStatement().executeUpdate("DELETE FROM conversation");

            try (PreparedStatement c =
                    connection.prepareStatement(
                            """
                            INSERT INTO conversation
                              (id, title, status, revision, created_at, updated_at, title_source, last_activity_at)
                            VALUES (?, '会话1', 'ACTIVE', 1, ?, ?, 'AUTO', ?)
                            """)) {
                c.setString(1, conversationId.asString());
                c.setString(2, now);
                c.setString(3, now);
                c.setString(4, now);
                c.executeUpdate();
            }
            try (PreparedStatement m =
                    connection.prepareStatement(
                            """
                            INSERT INTO message (id, conversation_id, role, content_json, sequence_no, created_at)
                            VALUES (?, ?, 'USER', '{"v":1,"text":"x"}', 1, ?)
                            """)) {
                m.setString(1, messageId.asString());
                m.setString(2, conversationId.asString());
                m.setString(3, now);
                m.executeUpdate();
            }
            try (PreparedStatement t =
                    connection.prepareStatement(
                            """
                            INSERT INTO turn (
                              id, conversation_id, client_request_id, input_message_id,
                              status, revision, execution_id, claim_expires_at, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, 'COMMITTING', 4, 'exec-1', ?, ?, ?)
                            """)) {
                t.setString(1, turnId.asString());
                t.setString(2, conversationId.asString());
                t.setString(3, "req-" + UUID.randomUUID());
                t.setString(4, messageId.asString());
                t.setString(5, Instant.now().plusSeconds(120).toString());
                t.setString(6, now);
                t.setString(7, now);
                t.executeUpdate();
            }
            connection.commit();
        }
    }

    @Test
    void committingCanPersistFailedWithOutbox() throws Exception {
        Instant now = Instant.now();
        Turn turn =
                Turn.reconstitute(
                        turnId,
                        conversationId,
                        "req",
                        messageId,
                        TurnStatus.COMMITTING,
                        4L,
                        "exec-1",
                        now.plusSeconds(60),
                        null,
                        now);
        long revision = turn.revision();
        turn.failUnrecoverableCommit(ErrorCodes.MISSING_COMMIT_PLAN, now);

        SaveTurnResult saved = terminalWriter.saveFailed(turn, revision, now);
        assertThat(saved).isInstanceOf(SaveTurnResult.Saved.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.FAILED.name());
        assertThat(countOutbox("TurnFailed")).isEqualTo(1);
    }

    private String statusOf(TurnId id) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT status FROM turn WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long countOutbox(String eventType) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?")) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
