package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryLifecycle;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
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
 * K02 1B-4：{@link TurnCommitter} 原子提交验收。
 *
 * <p>每个用例使用独立临时库；先种子 conversation / 用户 message / turn，再调用 commit。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnCommitterAtomicityTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private MemoryCommand memoryCommand;

    private ConversationId conversationId;
    private MessageId userMessageId;
    private TurnId turnId;

    @BeforeEach
    void seedCompletableTurn() throws Exception {
        conversationId = ConversationId.generate();
        userMessageId = MessageId.generate();
        turnId = TurnId.generate();
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // 同类测试共用一个临时库文件，先清空避免 outbox.sequence_no 等跨用例冲突
            clearBusinessTables(connection);
            insertConversation(connection, conversationId, now);
            insertUserMessage(connection, conversationId, userMessageId, now);
            insertTurn(
                    connection,
                    turnId,
                    conversationId,
                    userMessageId,
                    TurnStatus.RUNNING,
                    1L,
                    now);
            connection.commit();
        }
    }

    private static void clearBusinessTables(Connection connection) throws Exception {
        connection.createStatement().executeUpdate("DROP TRIGGER IF EXISTS fail_memory_insert");
        connection.createStatement().executeUpdate("DELETE FROM outbox_event");
        connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
        connection.createStatement().executeUpdate("DELETE FROM memory_record");
        connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
        connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
        connection.createStatement().executeUpdate("DELETE FROM message");
        connection.createStatement().executeUpdate("DELETE FROM conversation");
    }

    private long freezeReady(
            CommitTurnPlan.AssistantMessageDraft assistant,
            List<CommitTurnPlan.OutboxEventDraft> additional) {
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        new FreezeCommitPlan(
                                turnId,
                                1L,
                                "exec-seed",
                                Instant.parse("2026-09-18T12:00:00Z"),
                                assistant,
                                additional,
                                List.of(),
                                null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        return ((FreezeCommitResult.Frozen) frozen).committingRevision();
    }

    @Test
    void successfulCommitWritesMessageTurnAndOutboxAtomically() throws Exception {
        MessageId assistantId = MessageId.generate();
        String eventId = UUID.randomUUID().toString();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"你好\"}", 2);
        var events =
                List.of(
                        new CommitTurnPlan.OutboxEventDraft(
                                eventId,
                                "turn",
                                turnId.asString(),
                                "TurnCompleted",
                                "{\"ok\":true}",
                                1L));
        long committingRevision = freezeReady(assistant, events);
        CommitTurnPlan plan =
                CommitTurnPlan.completeTurn(turnId, committingRevision, "exec-seed", assistant, events, List.of(), null);

        CommitTurnResult result = turnCommitter.commit(plan);

        assertThat(result).isInstanceOf(CommitTurnResult.Committed.class);
        assertThat(((CommitTurnResult.Committed) result).newTurnRevision()).isEqualTo(committingRevision + 1);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistantId.asString()))
                    .isEqualTo(1);
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(scalar(connection, "SELECT output_message_id FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(assistantId.asString());
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?", "TurnCompleted"))
                    .isEqualTo(1);
            assertThat(scalar(connection, "SELECT payload_json FROM outbox_event WHERE event_type = ?", "TurnCompleted"))
                    .contains(assistantId.asString());
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan", null)).isZero();
        }
    }

    @Test
    void revisionConflictWritesNothing() throws Exception {
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1}", 2);
        long committingRevision = freezeReady(assistant, List.of());
        CommitTurnPlan plan =
                CommitTurnPlan.completeTurn(turnId, 99L, "exec-seed", assistant, List.of(), List.of(), null);

        CommitTurnResult result = turnCommitter.commit(plan);

        assertThat(result).isInstanceOf(CommitTurnResult.RevisionConflict.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistant.messageId().asString()))
                    .isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event", null)).isZero();
            assertThat(scalarLong(connection, "SELECT revision FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(committingRevision);
        }
    }

    @Test
    void memoryWriteFailureRollsBackAssistantOutboxAndMemoryAfterFrozenPlan() throws Exception {
        MessageId assistantId = MessageId.generate();
        var assistant = new CommitTurnPlan.AssistantMessageDraft(
                assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"记下了\"}", 2);
        var memory = new ApprovedMemoryChange(
                CompanionIdentity.YANHUO,
                "pref.tea",
                "用户喜欢龙井",
                ContentKind.USER_PREFERENCE,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                0.8,
                null,
                ApprovedMemoryChange.PROPOSE_TOOL_REMEMBER)
                .withExpectedGeneration(0);
        FreezeCommitResult frozen = turnCommitter.freezeCommit(
                FreezeCommitPlan.of(turnId, 1L, "exec-seed", Instant.parse("2026-09-18T12:00:00Z"),
                        assistant, List.of(), List.of(memory), null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        long committingRevision = ((FreezeCommitResult.Frozen) frozen).committingRevision();

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute(
                    "CREATE TRIGGER fail_memory_insert BEFORE INSERT ON memory_record "
                            + "BEGIN SELECT RAISE(ABORT, 'injected memory failure'); END");
        }
        CommitTurnResult result = turnCommitter.commit(CommitTurnPlan.completeTurn(
                turnId, committingRevision, "exec-seed", assistant, List.of(), List.of(memory), null));

        assertThat(result).isNotInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistantId.asString()))
                    .isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM memory_record", null)).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event", null)).isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
        }
    }

    @Test
    void staleHotTurnMemoryMutationIsSkippedWhileTurnStillCommits() throws Exception {
        var initial = (MemoryCommand.CommandResult.Applied) memoryCommand.applyReview(
                new ApprovedMemoryChange(
                        CompanionIdentity.YANHUO,
                        "pref.tea",
                        "用户喜欢龙井",
                        ContentKind.USER_PREFERENCE,
                        SourceKind.EXPLICIT,
                        MemoryScope.COMPANION,
                        0.8,
                        null,
                        ApprovedMemoryChange.PROPOSE_LLM_REVIEW)
                        .withExpectedGeneration(0));
        long observedGeneration;
        try (Connection connection = dataSource.getConnection();
                var ps = connection.prepareStatement(
                        "SELECT generation FROM memory_subject_generation "
                                + "WHERE companion_identity_id = ? AND subject_key = ?")) {
            ps.setString(1, CompanionIdentity.YANHUO.value());
            ps.setString(2, "pref.tea");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                observedGeneration = rs.getLong(1);
            }
        }
        var staleTurnMemory = new ApprovedMemoryChange(
                CompanionIdentity.YANHUO,
                "pref.tea",
                "旧 Turn 中的记忆草案",
                ContentKind.USER_PREFERENCE,
                SourceKind.EXPLICIT,
                MemoryScope.COMPANION,
                0.8,
                null,
                ApprovedMemoryChange.PROPOSE_TOOL_REMEMBER,
                observedGeneration);
        MessageId assistantId = MessageId.generate();
        var assistant = new CommitTurnPlan.AssistantMessageDraft(
                assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"收到\"}", 2);
        FreezeCommitResult frozen = turnCommitter.freezeCommit(
                FreezeCommitPlan.of(
                        turnId,
                        1L,
                        "exec-seed",
                        Instant.parse("2026-09-18T12:00:00Z"),
                        assistant,
                        List.of(),
                        List.of(staleTurnMemory),
                        null));
        long committingRevision = ((FreezeCommitResult.Frozen) frozen).committingRevision();

        assertThat(memoryCommand.correct(
                        initial.memoryId(),
                        1L,
                        new ApprovedMemoryChange(
                                CompanionIdentity.YANHUO,
                                "pref.tea",
                                "人工更正后的记忆",
                                ContentKind.USER_PREFERENCE,
                                SourceKind.EXPLICIT,
                                MemoryScope.COMPANION,
                                0.9,
                                null,
                                ApprovedMemoryChange.PROPOSE_HTTP_CORRECT)))
                .isInstanceOf(MemoryCommand.CommandResult.Applied.class);

        CommitTurnResult committed = turnCommitter.commit(CommitTurnPlan.completeTurn(
                turnId,
                committingRevision,
                "exec-seed",
                assistant,
                List.of(),
                List.of(staleTurnMemory),
                null));

        assertThat(committed).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(
                            connection,
                            "SELECT COUNT(*) FROM memory_record WHERE status = '"
                                    + MemoryLifecycle.ACTIVE.name()
                                    + "' AND content_json LIKE '%旧 Turn 中的记忆草案%'",
                            null))
                    .isZero();
            assertThat(count(
                            connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistantId.asString()))
                    .isEqualTo(1);
        }
    }

    @Test
    void runningStatusCannotCommitDirectly() throws Exception {
        MessageId assistantId = MessageId.generate();
        String eventId = UUID.randomUUID().toString();
        CommitTurnPlan plan =
                CommitTurnPlan.completeTurn(
                        turnId,
                        1L,
                        "exec-seed",
                        new CommitTurnPlan.AssistantMessageDraft(
                                assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"skip\"}", 2),
                        List.of(
                                new CommitTurnPlan.OutboxEventDraft(
                                        eventId,
                                        "turn",
                                        turnId.asString(),
                                        "TurnCompleted",
                                        "{}",
                                        1L)), List.of(), null);

        CommitTurnResult result = turnCommitter.commit(plan);

        assertThat(result).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) result).reasonCode()).isEqualTo("ILLEGAL_STATUS");
        assertThat(((CommitTurnResult.Rejected) result).detail()).contains("COMMITTING");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistantId.asString()))
                    .isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.RUNNING.name());
            assertThat(scalarLong(connection, "SELECT revision FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(1L);
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE id = ?", eventId))
                    .isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event", null)).isZero();
        }
    }

    @Test
    void unsupportedExtensionIsRejectedWithoutWrites() throws Exception {
        MessageId assistantId = MessageId.generate();
        CommitTurnPlan plan =
                new CommitTurnPlan(
                        turnId,
                        1L,
                        "exec-seed",
                        new CommitTurnPlan.AssistantMessageDraft(
                                assistantId, MessageRole.ASSISTANT, "{\"v\":1}", 2),
                        List.of(),
                        List.of(),
                        null,
                        "fake-task");

        CommitTurnResult result = turnCommitter.commit(plan);

        assertThat(result).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) result).reasonCode()).isEqualTo("UNSUPPORTED_EXTENSION");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistantId.asString()))
                    .isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.RUNNING.name());
        }
    }

    @Test
    void midCommitFailureRollsBackMessageAndTurnUpdate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute(
                    """
                    CREATE TRIGGER test_fail_completion BEFORE INSERT ON outbox_event
                    WHEN NEW.event_type = 'TurnCompleted'
                    BEGIN
                      SELECT RAISE(ABORT, 'forced outbox failure');
                    END
                    """);
        }
        try {
            var assistant =
                    new CommitTurnPlan.AssistantMessageDraft(
                            MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1}", 2);
            long committingRevision = freezeReady(assistant, List.of());
            CommitTurnPlan plan =
                    CommitTurnPlan.completeTurn(turnId, committingRevision, "exec-seed", assistant, List.of(), List.of(), null);

            CommitTurnResult failed = turnCommitter.commit(plan);
            assertThat(failed).isInstanceOf(CommitTurnResult.Rejected.class);
            assertThat(((CommitTurnResult.Rejected) failed).reasonCode()).isEqualTo("PERSISTENCE_FAILED");

            try (Connection connection = dataSource.getConnection()) {
                assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistant.messageId().asString()))
                        .isZero();
                assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                        .isEqualTo(TurnStatus.COMMITTING.name());
                assertThat(scalarLong(connection, "SELECT revision FROM turn WHERE id = ?", turnId.asString()))
                        .isEqualTo(committingRevision);
                assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event", null)).isZero();
                assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan", null)).isEqualTo(1);
            }
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("DROP TRIGGER IF EXISTS test_fail_completion");
            }
        }
    }

    @Test
    void wrongExecutionIdWritesNothing() throws Exception {
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1}", 2);
        long committingRevision = freezeReady(assistant, List.of());
        CommitTurnResult result =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                turnId, committingRevision, "someone-else", assistant, List.of(), List.of(), null));
        assertThat(result).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) result).reasonCode()).isEqualTo("OWNER_MISMATCH");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = ?", assistant.messageId().asString()))
                    .isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event", null)).isZero();
        }
    }

    @Test
    void mismatchedCompletionEventWritesNothing() {
        CommitTurnResult result =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                turnId,
                                1L,
                                "exec-seed",
                                new CommitTurnPlan.AssistantMessageDraft(
                                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1}", 2),
                                List.of(
                                        new CommitTurnPlan.OutboxEventDraft(
                                                UUID.randomUUID().toString(),
                                                "turn",
                                                "other-turn",
                                                "TurnCompleted",
                                                "{}",
                                                9L)), List.of(), null));
        assertThat(result).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) result).reasonCode()).isEqualTo("ILLEGAL_ARGUMENT");
    }

    @Test
    void completedRetryDoesNotWriteAnotherAnswer() throws Exception {
        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"一次\"}", 2);
        long committingRevision = freezeReady(assistant, List.of());
        assertThat(
                        turnCommitter.commit(
                                CommitTurnPlan.completeTurn(
                                        turnId, committingRevision, "exec-seed", assistant, List.of(), List.of(), null)))
                .isInstanceOf(CommitTurnResult.Committed.class);

        CommitTurnResult replay =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                turnId,
                                1L,
                                "exec-seed",
                                new CommitTurnPlan.AssistantMessageDraft(
                                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"第二次\"}", 3),
                                List.of(), List.of(), null));
        assertThat(replay).isInstanceOf(CommitTurnResult.Committed.class);
        assertThat(((CommitTurnResult.Committed) replay).newTurnRevision()).isEqualTo(committingRevision + 1);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'", null))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?", "TurnCompleted"))
                    .isEqualTo(1);
        }
    }

    @Test
    void laterCommitIsVisibleAfterEarlierCursor() throws Exception {
        TurnId later = TurnId.generate();
        MessageId laterInput = MessageId.generate();
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        String nowText = now.toString();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement message =
                    connection.prepareStatement(
                            """
                            INSERT INTO message (
                                id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                            ) VALUES (?, ?, NULL, 'USER', '{"v":1,"text":"later"}', 2, ?)
                            """)) {
                message.setString(1, laterInput.asString());
                message.setString(2, conversationId.asString());
                message.setString(3, nowText);
                message.executeUpdate();
            }
            insertTurn(connection, later, conversationId, laterInput, TurnStatus.RUNNING, 1L, nowText);
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "UPDATE conversation SET next_message_seq = 3 WHERE id = ?")) {
                ps.setString(1, conversationId.asString());
                ps.executeUpdate();
            }
        }

        var firstAssistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"B\"}", 2);
        long firstRevision = freezeReady(firstAssistant, List.of());
        assertThat(
                        turnCommitter.commit(
                                CommitTurnPlan.completeTurn(
                                        turnId, firstRevision, "exec-seed", firstAssistant, List.of(), List.of(), null)))
                .isInstanceOf(CommitTurnResult.Committed.class);
        long cursor;
        try (Connection connection = dataSource.getConnection()) {
            cursor = scalarLong(connection, "SELECT MAX(sequence_no) FROM outbox_event", null);
        }
        var laterAssistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"A\"}", 2);
        FreezeCommitResult laterFrozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(later, 1L, "exec-seed", now, laterAssistant, List.of(), List.of(), null));
        assertThat(laterFrozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        long laterRevision = ((FreezeCommitResult.Frozen) laterFrozen).committingRevision();
        assertThat(
                        turnCommitter.commit(
                                CommitTurnPlan.completeTurn(
                                        later, laterRevision, "exec-seed", laterAssistant, List.of(), List.of(), null)))
                .isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM outbox_event
                                WHERE sequence_no > ? AND aggregate_id = ?
                                """)) {
            ps.setLong(1, cursor);
            ps.setString(2, later.asString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static void insertConversation(Connection connection, ConversationId id, String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO conversation (id, title, status, revision, created_at, updated_at, next_message_seq)
                        VALUES (?, NULL, 'ACTIVE', 1, ?, ?, 2)
                        """)) {
            ps.setString(1, id.asString());
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static void insertUserMessage(
            Connection connection, ConversationId conversationId, MessageId messageId, String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO message (
                            id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                        ) VALUES (?, ?, NULL, ?, ?, 1, ?)
                        """)) {
            ps.setString(1, messageId.asString());
            ps.setString(2, conversationId.asString());
            ps.setString(3, MessageRole.USER.name());
            ps.setString(4, "{\"v\":1,\"text\":\"hi\"}");
            ps.setString(5, now);
            ps.executeUpdate();
        }
    }

    private static void insertTurn(
            Connection connection,
            TurnId turnId,
            ConversationId conversationId,
            MessageId inputMessageId,
            TurnStatus status,
            long revision,
            String now)
            throws Exception {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        INSERT INTO turn (
                            id, conversation_id, client_request_id, status, input_message_id,
                            output_message_id, execution_id, claim_expires_at, revision, error_code,
                            created_at, updated_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, NULL, 'exec-seed', ?, ?, NULL, ?, ?, NULL)
                        """)) {
            ps.setString(1, turnId.asString());
            ps.setString(2, conversationId.asString());
            ps.setString(3, "req-" + turnId.asString());
            ps.setString(4, status.name());
            ps.setString(5, inputMessageId.asString());
            ps.setString(6, Instant.parse(now).plusSeconds(3600).toString());
            ps.setLong(7, revision);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
        }
    }

    private static int count(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (arg != null) {
                ps.setString(1, arg);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
            if (arg != null) {
                ps.setString(1, arg);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
