package com.wannian.server.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.agent.AgentInput;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.AgentOutcome;
import com.wannian.server.kernel.agent.AgentTrace;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.ExecuteTurn;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 0.2.1-C：TurnEngine 编排验收（认领 → Assembler → Loop → 冻结 → 提交）。
 *
 * <p>用 {@link RecordingAgentLoop} 断言「不重跑 Loop」类不变量；不把 Fake 模型当 Loop 行为通过证据。
 * R01–R05 持久化面由既有 {@code ReceiveTurnIdempotencyTest} / {@code RecoverableCommitPlanTest} 等覆盖，
 * 本类补 TurnEngine 路径缺口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnEngineOrchestrationTest {

    private static final String SYSTEM = "你是万年，一个有帮助的助手。";
    private static final Duration LEASE = Duration.ofSeconds(60);

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
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void secondExecuteOnCompletedDoesNotCallLoopAgain() {
        RecordingAgentLoop loop = RecordingAgentLoop.replying("一次回答");
        TurnEngine engine = engine(loop);

        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        String userText = "你好";
        receive(conversationId, "c-replay-1", turnId, userText);

        ExecuteTurnResult first = engine.execute(command(turnId, userText));
        assertThat(first).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(loop.runCount()).isEqualTo(1);

        ExecuteTurnResult second = engine.execute(command(turnId, userText));
        assertThat(second).isInstanceOf(ExecuteTurnResult.AlreadyCompleted.class);
        assertThat(loop.runCount()).isEqualTo(1);
    }

    @Test
    void committingRecoveryCommitsFrozenPlanWithoutCallingLoop() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        receive(conversationId, "c-recover-1", turnId, "恢复");

        Instant now = Instant.parse("2026-09-22T03:00:00Z");
        Turn turn = turnRepository.find(turnId).orElseThrow();
        long revision = turn.revision();
        turn.claim(revision, new ExecutionClaim("owner-recover", now.plusSeconds(60)), now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);

        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        turn.start(now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);

        turn = turnRepository.find(turnId).orElseThrow();
        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"冻结答案\"}", 0);
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId, turn.revision(), "owner-recover", now, assistant, List.of(), List.of(), null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMMITTING.name());

        RecordingAgentLoop loop = RecordingAgentLoop.replying("不该出现");
        TurnEngine engine = engine(loop);

        ExecuteTurnResult outcome = engine.execute(command(turnId, "恢复"));
        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Replied.class);
        assertThat(((ExecuteTurnResult.Replied) outcome).text()).isEqualTo("冻结答案");
        assertThat(loop.runCount()).isZero();
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.COMPLETED.name());
        assertThat(assistantMessageCount()).isEqualTo(1);
    }

    @Test
    void assemblerPassesOriginalUserMessageSeparateFromExcerpt() {
        ConversationId conversationId = newConversation();
        TurnId priorTurn = TurnId.generate();
        receive(conversationId, "c-excerpt-prior", priorTurn, "上一句");
        RecordingAgentLoop priorLoop = RecordingAgentLoop.replying("先前回答");
        assertThat(engine(priorLoop).execute(command(priorTurn, "上一句")))
                .isInstanceOf(ExecuteTurnResult.Replied.class);

        TurnId turnId = TurnId.generate();
        String current = "  当前句带缩进  ";
        receive(conversationId, "c-excerpt-cur", turnId, current);

        RecordingAgentLoop loop = RecordingAgentLoop.replying("本轮回答");
        assertThat(engine(loop).execute(command(turnId, current)))
                .isInstanceOf(ExecuteTurnResult.Replied.class);

        assertThat(loop.lastInput()).isNotNull();
        assertThat(loop.lastInput().userMessage()).isEqualTo(current);
        assertThat(loop.lastInput().conversationExcerpt()).doesNotContain(current.trim());
        assertThat(loop.lastInput().conversationExcerpt()).contains("上一句");
    }

    @Test
    void concurrentDifferentKeysSerializeLoopPerConversation() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnA = TurnId.generate();
        TurnId turnB = TurnId.generate();
        receive(conversationId, "c-serial-a", turnA, "第一句");
        receive(conversationId, "c-serial-b", turnB, "第二句");

        CountDownLatch firstEnteredLoop = new CountDownLatch(1);
        CountDownLatch holdFirst = new CountDownLatch(1);
        AtomicInteger inLoop = new AtomicInteger();
        AtomicInteger maxInLoop = new AtomicInteger();
        AtomicInteger loopStarts = new AtomicInteger();

        AgentLoop serialLoop =
                (input, budget) -> {
                    loopStarts.incrementAndGet();
                    int depth = inLoop.incrementAndGet();
                    maxInLoop.accumulateAndGet(depth, Math::max);
                    try {
                        if (loopStarts.get() == 1) {
                            firstEnteredLoop.countDown();
                            assertThat(holdFirst.await(10, TimeUnit.SECONDS)).isTrue();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    } finally {
                        inLoop.decrementAndGet();
                    }
                    return new AgentOutcome.FinalResponse(
                            "答:" + input.userMessage(),
                            new ModelUsage(1, 1),
                            AgentTrace.of("serial"));
                };

        TurnEngine engine = engine(serialLoop);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExecuteTurnResult> first =
                    pool.submit(() -> engine.execute(command(turnA, "第一句")));
            assertThat(firstEnteredLoop.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ExecuteTurnResult> second =
                    pool.submit(() -> engine.execute(command(turnB, "第二句")));
            // 持锁期间第二 Turn 不得进 Loop：用重叠计数证明串行，不用短超时否定等待
            assertThat(inLoop.get()).isEqualTo(1);
            assertThat(loopStarts.get()).isEqualTo(1);
            assertThat(statusOf(turnB)).isEqualTo(TurnStatus.RECEIVED.name());
            assertThat(second.isDone()).isFalse();

            holdFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecuteTurnResult.Replied.class);
            assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecuteTurnResult.Replied.class);
            assertThat(maxInLoop.get()).isEqualTo(1);
            assertThat(loopStarts.get()).isEqualTo(2);
            assertThat(statusOf(turnA)).isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(statusOf(turnB)).isEqualTo(TurnStatus.COMPLETED.name());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void freezeRevisionConflictFailsAttemptLeavingNotRunning() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        receive(conversationId, "c-rev-conflict", turnId, "冲突");

        TurnCommitter conflicting =
                new TurnCommitter() {
                    @Override
                    public ReceiveTurnResult receive(ReceiveTurnPlan plan) {
                        return turnCommitter.receive(plan);
                    }

                    @Override
                    public FreezeCommitResult freezeCommit(FreezeCommitPlan plan) {
                        return new FreezeCommitResult.RevisionConflict(plan.turnId(), 99L);
                    }

                    @Override
                    public Optional<CommitTurnPlan> frozenCommitPlan(TurnId id) {
                        return turnCommitter.frozenCommitPlan(id);
                    }

                    @Override
                    public CommitTurnResult commit(CommitTurnPlan plan) {
                        return turnCommitter.commit(plan);
                    }
                };

        TurnEngine engine =
                new TurnEngine(
                        turnRepository,
                        conflicting,
                        new ContextAssembler(conversationStore),
                        RecordingAgentLoop.replying("不应提交"));

        ExecuteTurnResult outcome = engine.execute(command(turnId, "冲突"));
        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Held.class);
        assertThat(((ExecuteTurnResult.Held) outcome).code()).isEqualTo(ErrorCodes.REVISION_CONFLICT);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.FAILED.name());
        assertThat(assistantMessageCount()).isZero();
    }

    @Test
    void cancelledFailureFromLoopMarksTurnCancelled() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        receive(conversationId, "c-cancel-map", turnId, "取消");

        AgentLoop cancelling =
                (input, budget) -> new AgentOutcome.Cancelled(AgentTrace.of("映射取消"));
        ExecuteTurnResult outcome = engine(cancelling).execute(command(turnId, "取消"));

        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Cancelled.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.CANCELLED.name());
        assertThat(assistantMessageCount()).isZero();
    }

    @Test
    void modelFailureCancelledThroughDefaultLoopMarksDbCancelled() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        receive(conversationId, "c-cancel-failure", turnId, "取消失败码");

        ModelPort cancelledPort =
                (request, context) ->
                        new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
        DefaultAgentLoop loop = new DefaultAgentLoop(cancelledPort);

        ExecuteTurnResult outcome = engine(loop).execute(command(turnId, "取消失败码"));
        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Cancelled.class);
        assertThat(statusOf(turnId)).isEqualTo(TurnStatus.CANCELLED.name());
        assertThat(assistantMessageCount()).isZero();
    }

    @Test
    void staleOwnerAfterLoopDoesNotWriteAssistantMessage() throws Exception {
        ConversationId conversationId = newConversation();
        TurnId turnId = TurnId.generate();
        receive(conversationId, "c-stale-1", turnId, "迟到来的");

        RecordingAgentLoop loop =
                RecordingAgentLoop.replying(
                        "不应落库",
                        input -> {
                            try {
                                Turn current = turnRepository.find(turnId).orElseThrow();
                                try (Connection connection = dataSource.getConnection();
                                        var ps =
                                                connection.prepareStatement(
                                                        "UPDATE turn SET execution_id = ? WHERE id = ?")) {
                                    ps.setString(1, "other-owner");
                                    ps.setString(2, turnId.asString());
                                    assertThat(ps.executeUpdate()).isEqualTo(1);
                                }
                                assertThat(current.executionId()).isNotEqualTo("other-owner");
                            } catch (Exception ex) {
                                throw new IllegalStateException(ex);
                            }
                        });

        ExecuteTurnResult outcome = engine(loop).execute(command(turnId, "迟到来的"));
        assertThat(outcome).isInstanceOf(ExecuteTurnResult.Held.class);
        assertThat(((ExecuteTurnResult.Held) outcome).code()).isEqualTo(ErrorCodes.STALE_ATTEMPT);
        assertThat(loop.runCount()).isEqualTo(1);
        assertThat(assistantMessageCount()).isZero();
        assertThat(statusOf(turnId)).isNotEqualTo(TurnStatus.COMPLETED.name());
    }

    private TurnEngine engine(AgentLoop loop) {
        return new TurnEngine(
                turnRepository, turnCommitter, new ContextAssembler(conversationStore), loop);
    }

    private ConversationId newConversation() {
        ConversationId id = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(id)))
                .isInstanceOf(CreateConversationResult.Created.class);
        return id;
    }

    private void receive(
            ConversationId conversationId, String clientRequestId, TurnId turnId, String text) {
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                clientRequestId,
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        MessageId.generate(),
                                        MessageRole.USER,
                                        "{\"v\":1,\"text\":\"" + escape(text) + "\"}",
                                        0)));
        assertThat(received).isInstanceOf(ReceiveTurnResult.Accepted.class);
    }

    private static ExecuteTurn command(TurnId turnId, String userMessage) {
        return new ExecuteTurn(
                turnId, userMessage, SYSTEM, AgentBudget.of(3, 15, 30, Instant.now()), LEASE);
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

    private int assistantMessageCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery(
                                        "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class RecordingAgentLoop implements AgentLoop {
        private final AtomicInteger runs = new AtomicInteger();
        private final List<AgentInput> inputs = new ArrayList<>();
        private final String reply;
        private final java.util.function.Consumer<AgentInput> beforeReturn;

        private RecordingAgentLoop(String reply, java.util.function.Consumer<AgentInput> beforeReturn) {
            this.reply = reply;
            this.beforeReturn = beforeReturn;
        }

        static RecordingAgentLoop replying(String reply) {
            return new RecordingAgentLoop(reply, null);
        }

        static RecordingAgentLoop replying(
                String reply, java.util.function.Consumer<AgentInput> beforeReturn) {
            return new RecordingAgentLoop(reply, beforeReturn);
        }

        @Override
        public AgentOutcome run(AgentInput input, AgentBudget budget) {
            runs.incrementAndGet();
            inputs.add(input);
            if (beforeReturn != null) {
                beforeReturn.accept(input);
            }
            return new AgentOutcome.FinalResponse(reply, new ModelUsage(1, 1), AgentTrace.of("record"));
        }

        int runCount() {
            return runs.get();
        }

        AgentInput lastInput() {
            return inputs.isEmpty() ? null : inputs.get(inputs.size() - 1);
        }
    }
}
