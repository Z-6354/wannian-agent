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
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * K02 1B-5c：持久化主路径冒烟（无模型、无 HTTP）。
 *
 * <p>{@code create → receive → claim → start → beginCommit → commit}，并再开连接验证会话仍在。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PersistenceLineSmokeTest {

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

    @Autowired
    private TurnRepository turnRepository;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void createReceiveThenCommitPersistsFullChainAndSurvivesReopen() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);

        TurnId turnId = TurnId.generate();
        MessageId userMessageId = MessageId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                "line-req-1",
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        userMessageId,
                                        MessageRole.USER,
                                        "{\"v\":1,\"text\":\"整线测试\"}",
                                        1)));
        assertThat(received).isInstanceOf(ReceiveTurnResult.Accepted.class);
        assertThat(((ReceiveTurnResult.Accepted) received).replayed()).isFalse();

        MessageId assistantMessageId = MessageId.generate();
        long committingRevision =
                advanceToCommitting(turnId, assistantMessageId, "{\"v\":1,\"text\":\"收到\"}");

        CommitTurnResult committed =
                turnCommitter.commit(turnCommitter.frozenCommitPlan(turnId).orElseThrow());
        assertThat(committed).isInstanceOf(CommitTurnResult.Committed.class);
        assertThat(((CommitTurnResult.Committed) committed).newTurnRevision())
                .isEqualTo(committingRevision + 1);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT title FROM conversation WHERE id = ?", conversationId.asString()))
                    .isEqualTo("会话1");
            assertThat(count(connection, "SELECT COUNT(*) FROM message")).isEqualTo(2);
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(scalar(connection, "SELECT output_message_id FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(assistantMessageId.asString());
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'TurnCompleted'"))
                    .isEqualTo(1);
        }

        // 新开 JDBC 连接读同一文件，模拟进程重启后仍能看见会话
        Path dbFile = tempDataDir.resolve("wannian.db");
        try (Connection reopened =
                DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            assertThat(scalar(reopened, "SELECT title FROM conversation WHERE id = ?", conversationId.asString()))
                    .isEqualTo("会话1");
            assertThat(scalar(reopened, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
        }
    }

    /** receive 之后：claim → start → freezeCommit。返回 COMMITTING 时的 revision。 */
    private long advanceToCommitting(TurnId turnId, MessageId assistantMessageId, String contentJson) {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ExecutionClaim claim = new ExecutionClaim("local-primary", now.plusSeconds(60));

        Turn turn = turnRepository.find(turnId).orElseThrow();
        long revision = turn.revision();
        turn.claim(revision, claim, now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);

        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        turn.start(now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);

        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId,
                                revision,
                                "local-primary",
                                now,
                                new CommitTurnPlan.AssistantMessageDraft(
                                        assistantMessageId, MessageRole.ASSISTANT, contentJson, 0)));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        return ((FreezeCommitResult.Frozen) frozen).committingRevision();
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
