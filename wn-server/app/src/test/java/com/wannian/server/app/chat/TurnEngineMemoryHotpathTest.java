package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.relationship.RelationshipStore;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.turn.ExecuteTurn;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 0.2.3-E 档 A：热路径 remember / 密钥拒 / 关系 tool 经 TurnEngine 正式落库（可控 ModelPort，非 live）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnEngineMemoryHotpathTest {

    private static final String SYSTEM = "你是烟火。";
    private static final Duration LEASE = Duration.ofSeconds(60);

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.memory.review.tick-ms", () -> "3600000");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private ContextAssembler contextAssembler;

    @Autowired
    private ToolRuntime toolRuntime;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private RelationshipStore relationshipStore;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
            connection.createStatement().executeUpdate("DELETE FROM memory_review_job");
            connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
            connection.createStatement().executeUpdate("DELETE FROM memory_record");
            connection.createStatement().executeUpdate("DELETE FROM relationship_state");
        }
    }

    @Test
    void rememberFactCommitsActiveMemoryAndDoesNotExhaustLowDecisionBudget() throws Exception {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    int n = decides.incrementAndGet();
                    if (n == 1) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c1",
                                                BuiltinToolNames.REMEMBER_FACT,
                                                "{\"claim\":\"用户喜欢龙井\","
                                                        + "\"subjectKey\":\"pref.tea\","
                                                        + "\"contentKind\":\"USER_PREFERENCE\","
                                                        + "\"importance\":\"0.9\"}")),
                                null);
                    }
                    return new ModelOutcome.FinalAnswer("已记住你喜欢龙井", null);
                };

        TurnId turnId = receiveNewTurn("记住：我喜欢龙井");
        // max=1：若 remember_fact 计入决策，第 2 次 decide 前会耗尽；不计则 tool + final 可通过
        ExecuteTurnResult outcome =
                engine(model).execute(command(turnId, "记住：我喜欢龙井", 1));

        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(decides.get()).isEqualTo(2);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMPLETED.name());
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.subjectKey()).isEqualTo("pref.tea");
                            assertThat(row.claim()).isEqualTo("用户喜欢龙井");
                            assertThat(row.importance()).isEqualTo(0.9);
                        });
        assertThat(scalar("SELECT COUNT(*) FROM memory_record")).isEqualTo("1");
    }

    @Test
    void secretRememberIsRejectedAndLeavesMemoryEmptyWhileTurnCompletes() throws Exception {
        ModelPort model =
                (request, context) -> {
                    if (request.messages().stream().noneMatch(m -> "tool".equals(m.role()))) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c1",
                                                BuiltinToolNames.REMEMBER_FACT,
                                                "{\"claim\":\"账号密码是 hunter2\","
                                                        + "\"subjectKey\":\"secret.pw\","
                                                        + "\"contentKind\":\"USER_FACT\","
                                                        + "\"importance\":\"0.8\"}")),
                                null);
                    }
                    return new ModelOutcome.FinalAnswer("这条不能记", null);
                };

        TurnId turnId = receiveNewTurn("密码是 hunter2");
        ExecuteTurnResult outcome = engine(model).execute(command(turnId, "密码是 hunter2", 3));

        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMPLETED.name());
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();
        assertThat(scalar("SELECT COUNT(*) FROM memory_record")).isEqualTo("0");
    }

    @Test
    void updateRelationshipCommitsWithoutWritingMemory() throws Exception {
        ModelPort model =
                (request, context) -> {
                    if (request.messages().stream().noneMatch(m -> "tool".equals(m.role()))) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c1",
                                                BuiltinToolNames.UPDATE_RELATIONSHIP,
                                                "{\"preferredAddress\":\"小火\","
                                                        + "\"boundaries\":\"勿深夜打扰\","
                                                        + "\"reason\":\"用户偏好\"}")),
                                null);
                    }
                    return new ModelOutcome.FinalAnswer("称呼已更新", null);
                };

        TurnId turnId = receiveNewTurn("以后叫我小火");
        ExecuteTurnResult outcome = engine(model).execute(command(turnId, "以后叫我小火", 3));

        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMPLETED.name());
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();
        assertThat(relationshipStore.findByCompanion(CompanionIdentity.YANHUO))
                .get()
                .satisfies(
                        rel -> {
                            assertThat(rel.preferredAddress()).isEqualTo("小火");
                            assertThat(rel.boundaries()).isEqualTo("勿深夜打扰");
                        });
        assertThat(scalar("SELECT COUNT(*) FROM relationship_state")).isEqualTo("1");
        assertThat(scalar("SELECT COUNT(*) FROM memory_record")).isEqualTo("0");
    }

    private TurnEngine engine(ModelPort model) {
        return new TurnEngine(
                turnRepository,
                turnCommitter,
                contextAssembler,
                new DefaultAgentLoop(model, toolRuntime),
                memoryStore);
    }

    private TurnId receiveNewTurn(String text) {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                "req-" + turnId.asString(),
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        MessageId.generate(),
                                        MessageRole.USER,
                                        "{\"v\":1,\"text\":\"" + escape(text) + "\"}",
                                        0)));
        assertThat(received).isInstanceOf(ReceiveTurnResult.Accepted.class);
        return turnId;
    }

    private static ExecuteTurn command(TurnId turnId, String userMessage, int maxDecisions) {
        return new ExecuteTurn(
                turnId,
                userMessage,
                SYSTEM,
                AgentBudget.of(maxDecisions, 30, 60, Instant.now()),
                LEASE);
    }

    private String statusOf(TurnId turnId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                var ps = connection.prepareStatement("SELECT status FROM turn WHERE id = ?")) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
