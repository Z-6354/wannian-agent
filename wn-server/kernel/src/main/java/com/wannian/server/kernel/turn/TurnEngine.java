package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.agent.AgentInput;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.AgentOutcome;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.TurnSource;
import com.wannian.server.kernel.error.ErrorCodes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单次 USER 回合的调度（0.2.1-C）。
 *
 * <p>认领成功后才调用 {@link ContextAssembler} 与 {@link AgentLoop#run}，再经
 * {@link TurnCommitter} 冻结并提交。不调用模型、不拼 SQL、不发 SSE。
 *
 * <p>预算与人设由 {@link ExecuteTurn} 传入，不放进构造器：可读配置在 app。
 * 不依赖 DecisionPort / WorldAgent / Spring。
 *
 * <p>同会话 followup（方案 A）：{@code RECEIVED} 与 {@code COMMITTING} 恢复均按
 * {@code conversationId} 持进程内公平锁串行，双 POST 不双跑 Loop。不新增排队错误码。
 *
 * <p>已分类的 {@link TurnTransitionException} / {@link SaveTurnResult.Rejected} 保留其
 * {@link ErrorCodes}，不笼统改写成 CLAIM_FAILED。
 */
public final class TurnEngine {

    private static final System.Logger LOG = System.getLogger(TurnEngine.class.getName());

    private final TurnRepository turns;
    private final TurnCommitter turnCommitter;
    private final ContextAssembler assembler;
    private final AgentLoop agentLoop;
    private final ConcurrentHashMap<String, ReentrantLock> conversationLocks =
            new ConcurrentHashMap<>();

    /**
     * @param turns 加载回合并保存认领 / 开始执行；不得为 null
     * @param turnCommitter 冻结与提交；不得为 null
     * @param assembler 把已提交近讯装成循环输入；不得为 null
     * @param agentLoop 模型循环入口，类型是接口；不得为 null
     */
    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.turnCommitter = Objects.requireNonNull(turnCommitter, "turnCommitter");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
    }

    /**
     * 目的：把已接收的 USER 回合认领到 RUNNING，再组装上下文、跑 Loop、冻结并提交。
     * 输入保证：回合已经 receive；userMessage 是原文；budget 与 claimLease 由调用方按配置构造。
     * 输出保证：不返回 null。COMPLETED 与 COMMITTING 不调用 Loop。
     * 禁止：调用 ModelPort、拼 SQL、发 SSE、写 taskDraft、处理 WORLD 回合。
     */
    public ExecuteTurnResult execute(ExecuteTurn command) {
        Objects.requireNonNull(command, "command");

        Optional<Turn> found = turns.find(command.turnId());
        if (found.isEmpty()) {
            return new ExecuteTurnResult.Held(ErrorCodes.TURN_NOT_FOUND, "回合不存在");
        }
        Turn turn = found.get();

        return switch (turn.status()) {
            case COMPLETED -> new ExecuteTurnResult.AlreadyCompleted(turn.id());
            case COMMITTING, RECEIVED -> runConversationSerialized(turn.conversationId(), command);
            case CLAIMED, RUNNING, FAILED, CANCELLED ->
                    new ExecuteTurnResult.Held(
                            ErrorCodes.ILLEGAL_STATUS,
                            "回合状态为 " + turn.status() + "，不能开始这一次执行");
        };
    }

    /**
     * 同会话公平锁串行 RECEIVED 执行与 COMMITTING 恢复；入锁后重新加载，避免与下一 Turn 交错。
     */
    private ExecuteTurnResult runConversationSerialized(
            ConversationId conversationId, ExecuteTurn command) {
        String lockKey = conversationId.asString();
        ReentrantLock lock = conversationLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            Optional<Turn> latest = turns.find(command.turnId());
            if (latest.isEmpty()) {
                return new ExecuteTurnResult.Held(ErrorCodes.TURN_NOT_FOUND, "回合不存在");
            }
            Turn current = latest.get();
            return switch (current.status()) {
                case COMPLETED -> new ExecuteTurnResult.AlreadyCompleted(current.id());
                case COMMITTING -> recoverCommitting(current.id());
                case RECEIVED -> runReceived(current, command);
                case CLAIMED, RUNNING, FAILED, CANCELLED ->
                        new ExecuteTurnResult.Held(
                                ErrorCodes.ILLEGAL_STATUS,
                                "回合状态为 " + current.status() + "，不能开始这一次执行");
            };
        } finally {
            lock.unlock();
            // 无等待者时摘掉，避免会话锁永久堆积（仍可能与并发 computeIfAbsent 竞态，remove 按实例匹配）
            if (!lock.hasQueuedThreads()) {
                conversationLocks.remove(lockKey, lock);
            }
        }
    }

    private ExecuteTurnResult runReceived(Turn turn, ExecuteTurn command) {
        Instant now = Instant.now();
        ExecutionClaim claim = ExecutionClaim.attempt(now.plus(command.claimLease()));
        long revision = turn.revision();
        try {
            turn.claim(revision, claim, now);
        } catch (TurnTransitionException ex) {
            return held(ex);
        } catch (RuntimeException ex) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.CLAIM_FAILED, safeMessage(ex, "认领失败"));
        }
        ExecuteTurnResult claimSave = mapSaveFailure(turns.save(turn, revision, now));
        if (claimSave != null) {
            return claimSave;
        }

        turn = turns.find(turn.id()).orElse(turn);
        revision = turn.revision();
        now = Instant.now();
        try {
            turn.start(now);
        } catch (TurnTransitionException ex) {
            failAttempt(turn.id(), claim.executionId(), ex.reasonCode(), now);
            return held(ex);
        } catch (RuntimeException ex) {
            failAttempt(turn.id(), claim.executionId(), ErrorCodes.START_FAILED, now);
            return new ExecuteTurnResult.Held(
                    ErrorCodes.START_FAILED, safeMessage(ex, "无法开始执行"));
        }
        ExecuteTurnResult startSave = mapSaveFailure(turns.save(turn, revision, now));
        if (startSave instanceof ExecuteTurnResult.Held held) {
            failAttempt(turn.id(), claim.executionId(), held.code(), Instant.now());
            return held;
        }

        turn = turns.find(turn.id()).orElse(turn);

        AgentInput input =
                assembler.assemble(
                        new ContextAssembler.AssemblyRequest(
                                turn.conversationId(),
                                turn.id(),
                                TurnSource.USER,
                                turn.inputMessageId(),
                                command.userMessage(),
                                command.systemInstructions(),
                                ContextAssembler.DEFAULT_RECENT_MESSAGES,
                                null));
        AgentOutcome outcome = agentLoop.run(input, command.budget());

        turn = turns.find(turn.id()).orElse(turn);
        if (turn.status() != TurnStatus.RUNNING
                || !claim.executionId().equals(turn.executionId())) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.STALE_ATTEMPT, "本轮执行权已失效，不能提交结果");
        }

        return switch (outcome) {
            case AgentOutcome.FinalResponse answer ->
                    sealReply(turn, claim.executionId(), answer.text());
            case AgentOutcome.ControlledFailure failure -> {
                failAttempt(turn.id(), claim.executionId(), failure.errorCode(), Instant.now());
                yield new ExecuteTurnResult.Held(failure.errorCode(), failure.safeUserMessage());
            }
            case AgentOutcome.Cancelled ignored -> cancelAttempt(turn.id(), claim.executionId());
            case AgentOutcome.BackgroundAccepted ignored -> {
                failAttempt(
                        turn.id(),
                        claim.executionId(),
                        ErrorCodes.BACKGROUND_NOT_ENABLED,
                        Instant.now());
                yield new ExecuteTurnResult.Held(
                        ErrorCodes.BACKGROUND_NOT_ENABLED, "本轮尚未启用后台任务");
            }
        };
    }

    private ExecuteTurnResult sealReply(Turn turn, String executionId, String replyText) {
        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, contentEnvelope(replyText), 0);
        Instant now = Instant.now();
        long revision = turn.revision();
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(turn.id(), revision, executionId, now, assistant));
        if (frozen instanceof FreezeCommitResult.Frozen frozenOk) {
            CommitTurnPlan plan =
                    turnCommitter
                            .frozenCommitPlan(turn.id())
                            .orElseGet(
                                    () ->
                                            CommitTurnPlan.completeTurn(
                                                    turn.id(),
                                                    frozenOk.committingRevision(),
                                                    executionId,
                                                    assistant,
                                                    List.of()));
            CommitTurnResult committed = turnCommitter.commit(plan);
            if (!(committed instanceof CommitTurnResult.Committed)) {
                return mapCommitFailure(committed);
            }
            return new ExecuteTurnResult.Replied(replyText);
        }
        if (frozen instanceof FreezeCommitResult.RevisionConflict conflict) {
            failAttempt(turn.id(), executionId, ErrorCodes.REVISION_CONFLICT, Instant.now());
            return new ExecuteTurnResult.Held(
                    ErrorCodes.REVISION_CONFLICT,
                    "revision 冲突，实际 " + conflict.actualRevision());
        }
        FreezeCommitResult.Rejected rejected = (FreezeCommitResult.Rejected) frozen;
        failAttempt(turn.id(), executionId, rejected.reasonCode(), Instant.now());
        return new ExecuteTurnResult.Held(rejected.reasonCode(), rejected.detail());
    }

    private ExecuteTurnResult recoverCommitting(TurnId turnId) {
        Optional<CommitTurnPlan> frozen = turnCommitter.frozenCommitPlan(turnId);
        if (frozen.isEmpty()) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.MISSING_COMMIT_PLAN, "COMMITTING 缺少可恢复完成计划");
        }
        CommitTurnPlan plan = frozen.get();
        CommitTurnResult committed = turnCommitter.commit(plan);
        if (!(committed instanceof CommitTurnResult.Committed)) {
            return mapCommitFailure(committed);
        }
        try {
            return new ExecuteTurnResult.Replied(
                    ContextAssembler.textOf(plan.assistantMessage().contentJson()));
        } catch (RuntimeException ex) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.REPLY_MISSING, safeMessage(ex, "冻结计划缺少助手正文"));
        }
    }

    private ExecuteTurnResult cancelAttempt(TurnId turnId, String executionId) {
        Optional<Turn> latest = turns.find(turnId);
        if (latest.isEmpty()) {
            return new ExecuteTurnResult.Held(ErrorCodes.CANCEL_FAILED, "回合不存在，无法取消");
        }
        Turn current = latest.get();
        if (current.status() != TurnStatus.RUNNING
                || !executionId.equals(current.executionId())) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.STALE_ATTEMPT, "本轮执行权已失效，不能取消落库");
        }
        Instant now = Instant.now();
        long revision = current.revision();
        try {
            current.cancel(now);
        } catch (TurnTransitionException ex) {
            return held(ex);
        } catch (RuntimeException ex) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.CANCEL_FAILED, safeMessage(ex, "无法取消"));
        }
        SaveTurnResult saved = turns.save(current, revision, now);
        ExecuteTurnResult cancelSave = mapSaveFailure(saved);
        if (cancelSave != null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "cancelAttempt 落库失败 turn="
                                    + turnId.asString()
                                    + " status可能仍为 RUNNING result="
                                    + saved.getClass().getSimpleName());
            return cancelSave;
        }
        return new ExecuteTurnResult.Cancelled(turnId);
    }

    /**
     * 尽力把仍属于本次 attempt 的 CLAIMED / RUNNING 标为 FAILED。
     * 已进入 COMMITTING 或不是本次 executionId 时不改状态。落库失败只记日志，对外仍以 Held 为准。
     */
    private void failAttempt(TurnId turnId, String executionId, String code, Instant now) {
        Optional<Turn> latest = turns.find(turnId);
        if (latest.isEmpty()) {
            return;
        }
        Turn current = latest.get();
        if (current.status() != TurnStatus.CLAIMED && current.status() != TurnStatus.RUNNING) {
            return;
        }
        if (!executionId.equals(current.executionId())) {
            return;
        }
        long revision = current.revision();
        try {
            current.fail(code, now);
            SaveTurnResult saved = turns.save(current, revision, now);
            if (!(saved instanceof SaveTurnResult.Saved)) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () ->
                                "failAttempt 落库失败 turn="
                                        + turnId.asString()
                                        + " code="
                                        + code
                                        + " result="
                                        + saved.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "failAttempt 异常 turn="
                                    + turnId.asString()
                                    + " code="
                                    + code
                                    + " msg="
                                    + safeMessage(ex, ex.getClass().getSimpleName()));
        }
    }

    private static ExecuteTurnResult held(TurnTransitionException ex) {
        return new ExecuteTurnResult.Held(ex.reasonCode(), safeMessage(ex, ex.reasonCode()));
    }

    /**
     * @return 失败时的 Held；成功保存时返回 null
     */
    private static ExecuteTurnResult mapSaveFailure(SaveTurnResult saved) {
        return switch (saved) {
            case SaveTurnResult.Saved ignored -> null;
            case SaveTurnResult.RevisionConflict conflict ->
                    new ExecuteTurnResult.Held(
                            ErrorCodes.REVISION_CONFLICT,
                            "revision 冲突，实际 " + conflict.actualRevision());
            case SaveTurnResult.NotFound ignored ->
                    new ExecuteTurnResult.Held(ErrorCodes.TURN_NOT_FOUND, "回合不存在");
            case SaveTurnResult.Rejected rejected ->
                    new ExecuteTurnResult.Held(rejected.reasonCode(), rejected.detail());
        };
    }

    private static ExecuteTurnResult mapCommitFailure(CommitTurnResult committed) {
        return switch (committed) {
            case CommitTurnResult.Committed ignored ->
                    new ExecuteTurnResult.Held(ErrorCodes.INTERNAL_DEFECT, "unexpected committed");
            case CommitTurnResult.Rejected rejected ->
                    new ExecuteTurnResult.Held(rejected.reasonCode(), rejected.detail());
            case CommitTurnResult.RevisionConflict conflict ->
                    new ExecuteTurnResult.Held(
                            ErrorCodes.REVISION_CONFLICT,
                            "revision 冲突，实际 " + conflict.actualRevision());
        };
    }

    private static String safeMessage(Throwable ex, String fallback) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    /** 手写 v1 envelope，避免 kernel 依赖 Jackson。 */
    static String contentEnvelope(String text) {
        Objects.requireNonNull(text, "text");
        return "{\"v\":1,\"text\":\"" + escapeJson(text) + "\"}";
    }

    private static String escapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
