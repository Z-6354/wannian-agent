package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K02 1B-5b：receive 幂等与「会话必须先创建」验收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiveTurnIdempotencyTest {

    private static final String CONTENT_HI = "{\"v\":1,\"text\":\"你好\"}";

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void firstReceiveCreatesUserMessageAndReceivedTurn() throws Exception {
        ConversationId conversationId = createConversation();
        TurnId turnId = TurnId.generate();
        MessageId messageId = MessageId.generate();

        ReceiveTurnResult result =
                turnCommitter.receive(plan(conversationId, "req-1", turnId, messageId, CONTENT_HI));

        assertThat(result).isInstanceOf(ReceiveTurnResult.Accepted.class);
        ReceiveTurnResult.Accepted accepted = (ReceiveTurnResult.Accepted) result;
        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.turnId()).isEqualTo(turnId);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message")).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM turn")).isEqualTo(1);
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.RECEIVED.name());
        }
    }

    @Test
    void sameClientRequestIdAndContentReplaysWithoutExtraRows() throws Exception {
        ConversationId conversationId = createConversation();
        TurnId turnId = TurnId.generate();
        MessageId messageId = MessageId.generate();
        String clientRequestId = "req-replay";

        ReceiveTurnResult first =
                turnCommitter.receive(
                        plan(conversationId, clientRequestId, turnId, messageId, CONTENT_HI));
        assertThat(first).isInstanceOf(ReceiveTurnResult.Accepted.class);

        ReceiveTurnResult second =
                turnCommitter.receive(
                        plan(
                                conversationId,
                                clientRequestId,
                                TurnId.generate(),
                                MessageId.generate(),
                                CONTENT_HI));

        assertThat(second).isInstanceOf(ReceiveTurnResult.Accepted.class);
        ReceiveTurnResult.Accepted accepted = (ReceiveTurnResult.Accepted) second;
        assertThat(accepted.replayed()).isTrue();
        assertThat(accepted.turnId()).isEqualTo(turnId);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM turn")).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM message")).isEqualTo(1);
        }
    }

    @Test
    void sameClientRequestIdDifferentContentConflicts() {
        ConversationId conversationId = createConversation();
        String clientRequestId = "req-conflict";

        turnCommitter.receive(
                plan(
                        conversationId,
                        clientRequestId,
                        TurnId.generate(),
                        MessageId.generate(),
                        CONTENT_HI));

        ReceiveTurnResult second =
                turnCommitter.receive(
                        plan(
                                conversationId,
                                clientRequestId,
                                TurnId.generate(),
                                MessageId.generate(),
                                "{\"v\":1,\"text\":\"另一句\"}"));

        assertThat(second).isInstanceOf(ReceiveTurnResult.Conflict.class);
    }

    @Test
    void missingConversationIsRejected() {
        ReceiveTurnResult result =
                turnCommitter.receive(
                        plan(
                                ConversationId.generate(),
                                "req-missing-conv",
                                TurnId.generate(),
                                MessageId.generate(),
                                CONTENT_HI));

        assertThat(result).isInstanceOf(ReceiveTurnResult.Rejected.class);
        assertThat(((ReceiveTurnResult.Rejected) result).reasonCode())
                .isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void sameKeyInAnotherConversationConflictsAndWritesNothing() throws Exception {
        ConversationId first = createConversation();
        ConversationId second = createConversation();
        String key = "shared-key";
        turnCommitter.receive(plan(first, key, TurnId.generate(), MessageId.generate(), CONTENT_HI));

        ReceiveTurnResult cross =
                turnCommitter.receive(plan(second, key, TurnId.generate(), MessageId.generate(), CONTENT_HI));
        assertThat(cross).isInstanceOf(ReceiveTurnResult.Conflict.class);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT COUNT(*) FROM message WHERE conversation_id = ?")) {
            ps.setString(1, second.asString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            assertThat(count(connection, "SELECT COUNT(*) FROM turn")).isEqualTo(1);
        }
    }

    @Test
    void serverAssignsSequenceAndIgnoresClientNumber() throws Exception {
        ConversationId conversationId = createConversation();
        turnCommitter.receive(plan(conversationId, "seq-a", TurnId.generate(), MessageId.generate(), CONTENT_HI));
        turnCommitter.receive(
                plan(
                        conversationId,
                        "seq-b",
                        TurnId.generate(),
                        MessageId.generate(),
                        "{\"v\":1,\"text\":\"下一句\"}"));
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery(
                                        "SELECT sequence_no FROM message ORDER BY sequence_no")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void concurrentSameRequestDoesNotCreateASecondTurn() throws Exception {
        ConversationId conversationId = createConversation();
        String key = "concurrent-key";
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<ReceiveTurnResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return turnCommitter.receive(
                                            plan(
                                                    conversationId,
                                                    key,
                                                    TurnId.generate(),
                                                    MessageId.generate(),
                                                    CONTENT_HI));
                                }));
            }
            for (java.util.concurrent.Future<ReceiveTurnResult> future : futures) {
                ReceiveTurnResult result = future.get();
                assertThat(result)
                        .isInstanceOfAny(ReceiveTurnResult.Accepted.class, ReceiveTurnResult.Rejected.class);
                if (result instanceof ReceiveTurnResult.Rejected rejected) {
                    assertThat(rejected.reasonCode()).isEqualTo("RETRYABLE_BUSY");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        turnCommitter.receive(plan(conversationId, key, TurnId.generate(), MessageId.generate(), CONTENT_HI));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM turn")).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM message")).isEqualTo(1);
        }
    }

    private ConversationId createConversation() {
        ConversationId id = ConversationId.generate();
        CreateConversationResult created =
                conversationStore.create(CreateConversationCommand.of(id));
        assertThat(created).isInstanceOf(CreateConversationResult.Created.class);
        return id;
    }

    private static ReceiveTurnPlan plan(
            ConversationId conversationId,
            String clientRequestId,
            TurnId turnId,
            MessageId messageId,
            String contentJson) {
        return new ReceiveTurnPlan(
                conversationId,
                clientRequestId,
                turnId,
                new ReceiveTurnPlan.UserMessageDraft(
                        messageId, MessageRole.USER, contentJson, 1));
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
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
}
