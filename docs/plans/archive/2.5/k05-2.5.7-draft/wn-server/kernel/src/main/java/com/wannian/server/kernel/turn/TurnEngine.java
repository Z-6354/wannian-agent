package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.agent.AgentInput;
import com.wannian.server.kernel.agent.AgentLoop;
import com.wannian.server.kernel.agent.AgentOutcome;
import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.agent.TurnSource;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.TurnMemoryPending;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskReviewPending;
import com.wannian.server.kernel.task.TaskReviewPendingRepository;
import com.wannian.server.kernel.task.TaskReviewStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单次 USER 回合的调度（2.1.3）。
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
    private final MemoryStore memoryStore;
    private final RunJournal journal;
    private final JournalSettings journalSettings;
    private final TurnTerminalWriter terminalWriter;
    private final TurnRunListener runListener;
    /** 近讯条数；生产由 app {@code wannian.context.recent-message-limit} 注入，测试可省略用默认。 */
    private final int recentMessageLimit;
    /** 2.5.5：可空；null 时 BackgroundAccepted 仍 Held。 */
    private final TaskReviewPendingRepository taskReviews;
    private final Duration taskReviewTtl;
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
        this(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                null,
                RunJournal.noop(),
                JournalSettings.DEFAULT,
                TurnTerminalWriter.viaRepository(turns),
                TurnRunListener.NOOP,
                ContextAssembler.DEFAULT_RECENT_MESSAGES);
    }

    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore) {
        this(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                memoryStore,
                RunJournal.noop(),
                JournalSettings.DEFAULT,
                TurnTerminalWriter.viaRepository(turns),
                TurnRunListener.NOOP,
                ContextAssembler.DEFAULT_RECENT_MESSAGES);
    }

    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal journal,
            JournalSettings journalSettings) {
        this(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                memoryStore,
                journal,
                journalSettings,
                TurnTerminalWriter.viaRepository(turns),
                TurnRunListener.NOOP,
                ContextAssembler.DEFAULT_RECENT_MESSAGES);
    }

    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal journal,
            JournalSettings journalSettings,
            TurnTerminalWriter terminalWriter,
            TurnRunListener runListener) {
        this(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                memoryStore,
                journal,
                journalSettings,
                terminalWriter,
                runListener,
                ContextAssembler.DEFAULT_RECENT_MESSAGES);
    }

    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal journal,
            JournalSettings journalSettings,
            TurnTerminalWriter terminalWriter,
            TurnRunListener runListener,
            int recentMessageLimit) {
        this(
                turns,
                turnCommitter,
                assembler,
                agentLoop,
                memoryStore,
                journal,
                journalSettings,
                terminalWriter,
                runListener,
                recentMessageLimit,
                null,
                Duration.ofMinutes(30));
    }

    public TurnEngine(
            TurnRepository turns,
            TurnCommitter turnCommitter,
            ContextAssembler assembler,
            AgentLoop agentLoop,
            MemoryStore memoryStore,
            RunJournal journal,
            JournalSettings journalSettings,
            TurnTerminalWriter terminalWriter,
            TurnRunListener runListener,
            int recentMessageLimit,
            TaskReviewPendingRepository taskReviews,
            Duration taskReviewTtl) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.turnCommitter = Objects.requireNonNull(turnCommitter, "turnCommitter");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.agentLoop = Objects.requireNonNull(agentLoop);
        this.memoryStore = memoryStore;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.journalSettings = Objects.requireNonNull(journalSettings, "journalSettings");
        this.terminalWriter = Objects.requireNonNull(terminalWriter, "terminalWriter");
        this.runListener = Objects.requireNonNull(runListener, "runListener");
        if (recentMessageLimit <= 0) {
            throw new IllegalArgumentException("recentMessageLimit 须为正");
        }
        this.recentMessageLimit = recentMessageLimit;
        this.taskReviews = taskReviews;
        this.taskReviewTtl =
                taskReviewTtl == null || taskReviewTtl.isZero() || taskReviewTtl.isNegative()
                        ? Duration.ofMinutes(30)
                        : taskReviewTtl;
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
        try {
            runListener.onRunning(turn.conversationId(), turn.id(), claim.executionId());
        } catch (RuntimeException ignored) {
            // 运行投影不得打断 Turn
        }

        AtomicInteger journalStep = new AtomicInteger(0);
        journalUserInput(turn, command.userMessage(), journalStep);

        TurnMemoryPending pending =
                memoryStore == null
                        ? new InMemoryTurnMemoryPending(Map.of(), command.personaSnapshot()==null?CompanionIdentity.YANHUO:command.personaSnapshot().companionIdentity(), command.userMessage(),turn.conversationId(),turn.id())
                        : new InMemoryTurnMemoryPending(
                                memoryStore.subjectGenerations(command.personaSnapshot()==null?CompanionIdentity.YANHUO:command.personaSnapshot().companionIdentity()), command.personaSnapshot()==null?CompanionIdentity.YANHUO:command.personaSnapshot().companionIdentity(), command.userMessage(),turn.conversationId(),turn.id());
        // 2.5.7：idle-task: 前缀 → SYSTEM（有界唤模）；其余仍 USER
        TurnSource turnSource =
                isIdleTaskDelivery(turn.clientRequestId()) ? TurnSource.SYSTEM : TurnSource.USER;
        AgentInput input =
                assembler.assemble(
                        new ContextAssembler.AssemblyRequest(
                                turn.conversationId(),
                                turn.id(),
                                turnSource,
                                turn.inputMessageId(),
                                command.userMessage(),
                                command.systemInstructions(),
                                recentMessageLimit,
                                null,
                                pending,
                                isRewriteRequest(turn.clientRequestId(), command.userMessage()),
                                command.personaSnapshot()));
        AgentOutcome outcome = agentLoop.run(input, command.budget());

        turn = turns.find(turn.id()).orElse(turn);
        if (turn.status() != TurnStatus.RUNNING
                || !claim.executionId().equals(turn.executionId())) {
            journalFinalize(turn, "STALE", ErrorCodes.STALE_ATTEMPT, journalStep);
            return new ExecuteTurnResult.Held(
                    ErrorCodes.STALE_ATTEMPT, "本轮执行权已失效，不能提交结果");
        }

        return switch (outcome) {
            case AgentOutcome.FinalResponse answer -> {
                ExecuteTurnResult sealed =
                        sealReply(turn, claim.executionId(), answer.text(), pending);
                if (sealed instanceof ExecuteTurnResult.Replied) {
                    journalFinalize(turn, "COMPLETED", null, journalStep);
                } else if (sealed instanceof ExecuteTurnResult.Held held) {
                    journalFinalize(turn, "FAILED", held.code(), journalStep);
                }
                yield sealed;
            }
            case AgentOutcome.ControlledFailure failure -> {
                failAttempt(turn.id(), claim.executionId(), failure.errorCode(), Instant.now());
                notifyAborted(turn,claim.executionId());
                journalFinalize(turn, "FAILED", failure.errorCode(), journalStep);
                yield new ExecuteTurnResult.Held(failure.errorCode(), failure.safeUserMessage());
            }
            case AgentOutcome.Cancelled ignored -> {
                ExecuteTurnResult cancelled = cancelAttempt(turn.id(), claim.executionId());
                notifyAborted(turn,claim.executionId());
                journalFinalize(turn, "CANCELLED", ErrorCodes.CANCELLED, journalStep);
                yield cancelled;
            }
            case AgentOutcome.BackgroundAccepted accepted -> {
                if (taskReviews == null) {
                    failAttempt(
                            turn.id(),
                            claim.executionId(),
                            ErrorCodes.BACKGROUND_NOT_ENABLED,
                            Instant.now());
                    notifyAborted(turn, claim.executionId());
                    journalFinalize(turn, "FAILED", ErrorCodes.BACKGROUND_NOT_ENABLED, journalStep);
                    yield new ExecuteTurnResult.Held(
                            ErrorCodes.BACKGROUND_NOT_ENABLED, "本轮尚未启用后台任务审核");
                }
                Instant pendingNow = Instant.now();
                TaskReviewId reviewId = TaskReviewId.generate();
                TaskReviewPending pendingReview =
                        new TaskReviewPending(
                                reviewId,
                                turn.conversationId(),
                                turn.id(),
                                accepted.proposal(),
                                accepted.acknowledgementText(),
                                TaskReviewStatus.PENDING,
                                pendingNow,
                                pendingNow.plus(taskReviewTtl));
                try {
                    taskReviews.insert(pendingReview);
                } catch (RuntimeException ex) {
                    failAttempt(
                            turn.id(),
                            claim.executionId(),
                            ErrorCodes.PERSISTENCE_FAILED,
                            Instant.now());
                    notifyAborted(turn, claim.executionId());
                    journalFinalize(turn, "FAILED", ErrorCodes.PERSISTENCE_FAILED, journalStep);
                    yield new ExecuteTurnResult.Held(
                            ErrorCodes.PERSISTENCE_FAILED, "无法保存待审后台提案");
                }
                String pendingText =
                        "待你确认后台任务（reviewId="
                                + reviewId.asString()
                                + "）。确认后才会开始执行。";
                ExecuteTurnResult sealed =
                        sealReply(turn, claim.executionId(), pendingText, pending);
                if (sealed instanceof ExecuteTurnResult.Replied) {
                    journalFinalize(turn, "COMPLETED", null, journalStep);
                } else if (sealed instanceof ExecuteTurnResult.Held held) {
                    journalFinalize(turn, "FAILED", held.code(), journalStep);
                }
                yield sealed;
            }
        };
    }

    private void journalUserInput(Turn turn, String userMessage, AtomicInteger journalStep) {
        try {
            Instant now = Instant.now();
            boolean full = journalSettings.includeFullMessages();
            int max = journalSettings.maxPayloadChars();
            String request =
                    JournalJson.object(
                            "inputMessageId",
                            turn.inputMessageId().asString(),
                            "text",
                            full
                                    ? JournalJson.clip(userMessage, max)
                                    : Integer.toHexString(userMessage.hashCode()));
            journal.append(
                    RunJournalEntry.of(
                            turn.id().asString(),
                            turn.conversationId().asString(),
                            journalStep.incrementAndGet(),
                            JournalActor.USER,
                            JournalKind.USER_INPUT,
                            request,
                            null,
                            "SUCCEEDED",
                            null,
                            now,
                            now));
        } catch (RuntimeException ignored) {
            // 账本不得打断 Turn
        }
    }

    private void journalFinalize(
            Turn turn, String status, String errorCode, AtomicInteger journalStep) {
        try {
            Instant now = Instant.now();
            String result =
                    JournalJson.object(
                            "turnStatus",
                            status,
                            "errorCode",
                            errorCode);
            journal.append(
                    RunJournalEntry.of(
                            turn.id().asString(),
                            turn.conversationId().asString(),
                            journalStep.incrementAndGet(),
                            JournalActor.AGENT,
                            JournalKind.FINALIZE,
                            null,
                            result,
                            status,
                            errorCode,
                            now,
                            now));
        } catch (RuntimeException ignored) {
            // 账本不得打断 Turn
        }
    }

    private ExecuteTurnResult sealReply(
            Turn turn, String executionId, String replyText, TurnMemoryPending pending) {
        return sealReply(turn, executionId, replyText, pending, null);
    }

    private ExecuteTurnResult sealReply(
            Turn turn,
            String executionId,
            String replyText,
            TurnMemoryPending pending,
            TaskDraft taskDraft) {
        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, contentEnvelope(replyText), 0);
        Instant now = Instant.now();
        long revision = turn.revision();
        List<ApprovedMemoryChange> memories =
                refreshMemoryGenerations(pending.snapshotMemories());
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turn.id(),
                                revision,
                                executionId,
                                now,
                                assistant,
                                List.of(),
                                memories,
                                pending.snapshotRelationshipOrNull(),
                                taskDraft));
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
                                                    List.of(),
                                                    memories,
                                                    pending.snapshotRelationshipOrNull(),
                                                    taskDraft));
            CommitTurnResult committed = turnCommitter.commit(plan);
            if (!(committed instanceof CommitTurnResult.Committed)) {
                notifyAborted(turn, executionId);
                return mapCommitFailure(committed);
            }
            notifyCompleted(turn, executionId);
            return new ExecuteTurnResult.Replied(replyText);
        }
        if (frozen instanceof FreezeCommitResult.RevisionConflict conflict) {
            failAttempt(turn.id(), executionId, ErrorCodes.REVISION_CONFLICT, Instant.now());
            notifyAborted(turn, executionId);
            return new ExecuteTurnResult.Held(
                    ErrorCodes.REVISION_CONFLICT,
                    "revision 冲突，实际 " + conflict.actualRevision());
        }
        FreezeCommitResult.Rejected rejected = (FreezeCommitResult.Rejected) frozen;
        failAttempt(turn.id(), executionId, rejected.reasonCode(), Instant.now());
        notifyAborted(turn, executionId);
        return new ExecuteTurnResult.Held(rejected.reasonCode(), rejected.detail());
    }

    /**
     * 2.5.5：用户确认后台提案后，开短 Turn 提交确认文案 + {@link TaskDraft}（不跑 Loop）。
     */
    public ExecuteTurnResult commitTaskReviewAcceptance(
            ConversationId conversationId,
            String clientRequestId,
            String acknowledgementText,
            TaskDraft taskDraft,
            Duration claimLease) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(acknowledgementText, "acknowledgementText");
        Objects.requireNonNull(taskDraft, "taskDraft");
        Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease 须为正");
        }
        String ack = acknowledgementText.trim();
        if (ack.isEmpty()) {
            return new ExecuteTurnResult.Held(ErrorCodes.ILLEGAL_ARGUMENT, "确认文案不能为空");
        }

        TurnId turnId = TurnId.generate();
        MessageId userMessageId = MessageId.generate();
        ReceiveTurnResult received =
                turnCommitter.receive(
                        new ReceiveTurnPlan(
                                conversationId,
                                clientRequestId,
                                turnId,
                                new ReceiveTurnPlan.UserMessageDraft(
                                        userMessageId,
                                        MessageRole.USER,
                                        contentEnvelope("[确认后台任务]"),
                                        0)));
        if (!(received instanceof ReceiveTurnResult.Accepted accepted)) {
            if (received instanceof ReceiveTurnResult.Rejected rejected) {
                return new ExecuteTurnResult.Held(rejected.reasonCode(), rejected.detail());
            }
            if (received instanceof ReceiveTurnResult.Conflict conflict) {
                return new ExecuteTurnResult.Held(
                        ErrorCodes.CLIENT_REQUEST_CONFLICT,
                        "确认请求冲突: " + conflict.existingTurnId().asString());
            }
            return new ExecuteTurnResult.Held(ErrorCodes.PERSISTENCE_FAILED, "无法接收确认回合");
        }
        turnId = accepted.turnId();

        String lockKey = conversationId.asString();
        ReentrantLock lock = conversationLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            Optional<Turn> found = turns.find(turnId);
            if (found.isEmpty()) {
                return new ExecuteTurnResult.Held(ErrorCodes.TURN_NOT_FOUND, "确认回合不存在");
            }
            Turn turn = found.get();
            Instant now = Instant.now();
            ExecutionClaim claim = ExecutionClaim.attempt(now.plus(claimLease));
            long revision = turn.revision();
            try {
                turn.claim(revision, claim, now);
            } catch (TurnTransitionException ex) {
                return held(ex);
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
            }
            ExecuteTurnResult startSave = mapSaveFailure(turns.save(turn, revision, now));
            if (startSave instanceof ExecuteTurnResult.Held held) {
                failAttempt(turn.id(), claim.executionId(), held.code(), Instant.now());
                return held;
            }
            turn = turns.find(turn.id()).orElse(turn);
            TurnMemoryPending emptyPending =
                    new InMemoryTurnMemoryPending(
                            Map.of(),
                            CompanionIdentity.YANHUO,
                            "[确认后台任务]",
                            turn.conversationId(),
                            turn.id());
            return sealReply(turn, claim.executionId(), ack, emptyPending, taskDraft);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                conversationLocks.remove(lockKey, lock);
            }
        }
    }

    /**
     * 将 pending 中的 expectedGeneration 换成 Freeze 当下的库内值。
     * 无快照（legacy / 无 MemoryStore）的条目原样保留。
     */
    private List<ApprovedMemoryChange> refreshMemoryGenerations(
            List<ApprovedMemoryChange> changes) {
        if (memoryStore == null || changes.isEmpty()) {
            return changes;
        }
        Map<String, Map<String, Long>> byCompanion = new HashMap<>();
        List<ApprovedMemoryChange> out = new ArrayList<>(changes.size());
        for (ApprovedMemoryChange change : changes) {
            if (change.expectedGeneration() == null || change.expectedGeneration() < 0) {
                out.add(change);
                continue;
            }
            CompanionIdentity companion = change.companionIdentity();
            Map<String, Long> gens =
                    byCompanion.computeIfAbsent(
                            companion.value(), id -> memoryStore.subjectGenerations(companion));
            long fresh = gens.getOrDefault(change.subjectKey(), 0L);
            out.add(
                    fresh == change.expectedGeneration()
                            ? change
                            : change.withExpectedGeneration(fresh));
        }
        return List.copyOf(out);
    }

    private ExecuteTurnResult recoverCommitting(TurnId turnId) {
        Optional<CommitTurnPlan> frozen = turnCommitter.frozenCommitPlan(turnId);
        if (frozen.isEmpty()) {
            return failUnrecoverableCommitting(turnId, ErrorCodes.MISSING_COMMIT_PLAN);
        }
        CommitTurnPlan plan = frozen.get();
        CommitTurnResult committed = turnCommitter.commit(plan);
        if (!(committed instanceof CommitTurnResult.Committed)) {
            return mapCommitFailure(committed);
        }
        turns.find(turnId).ifPresent(t->notifyCompleted(t,plan.expectedExecutionId()));
        try {
            return new ExecuteTurnResult.Replied(
                    ContextAssembler.textOf(plan.assistantMessage().contentJson()));
        } catch (RuntimeException ex) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.REPLY_MISSING, safeMessage(ex, "冻结计划缺少助手正文"));
        }
    }

    /** COMMITTING 且无冻结计划：标 FAILED + Outbox，解除同会话堵塞。 */
    private ExecuteTurnResult failUnrecoverableCommitting(TurnId turnId, String code) {
        Optional<Turn> found = turns.find(turnId);
        if (found.isEmpty()) {
            return new ExecuteTurnResult.Held(code, "COMMITTING 回合不存在");
        }
        Turn turn = found.get();
        if (turn.status() != TurnStatus.COMMITTING) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.STALE_ATTEMPT, "期望 COMMITTING，实际 " + turn.status());
        }
        Instant now = Instant.now();
        long revision = turn.revision();
        try {
            turn.failUnrecoverableCommit(code, now);
        } catch (TurnTransitionException ex) {
            return held(ex);
        } catch (RuntimeException ex) {
            return new ExecuteTurnResult.Held(
                    ErrorCodes.PERSISTENCE_FAILED, safeMessage(ex, "无法结束不可恢复 COMMITTING"));
        }
        SaveTurnResult saved = terminalWriter.saveFailed(turn, revision, now);
        ExecuteTurnResult failSave = mapSaveFailure(saved);
        if (failSave != null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () ->
                            "failUnrecoverableCommitting 落库失败 turn="
                                    + turnId.asString()
                                    + " result="
                                    + saved.getClass().getSimpleName());
            return failSave;
        }
        notifyAborted(turn,turn.executionId());
        return new ExecuteTurnResult.Held(code, "COMMITTING 缺少可恢复完成计划，已标 FAILED");
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
        SaveTurnResult saved = terminalWriter.saveCancelled(current, revision, now);
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
            SaveTurnResult saved = terminalWriter.saveFailed(current, revision, now);
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

    private void notifyCompleted(Turn turn,String executionId) {
        try { runListener.onCompleted(turn.conversationId(),turn.id(),executionId); }
        catch(RuntimeException ex) { LOG.log(System.Logger.Level.WARNING,"完成回合后处理待切换角色失败",ex); }
    }

    private void notifyAborted(Turn turn,String executionId) {
        try { runListener.onAborted(turn.conversationId(),turn.id(),executionId); }
        catch(RuntimeException ex) { LOG.log(System.Logger.Level.WARNING,"终止回合后清理待切换角色失败",ex); }
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

    /** clientRequestId 以 rewrite: 开头，或用户正文为「换一种说法」。 */
    static boolean isRewriteRequest(String clientRequestId, String userMessage) {
        if (clientRequestId != null && clientRequestId.startsWith("rewrite:")) {
            return true;
        }
        if (userMessage == null) {
            return false;
        }
        String t = userMessage.strip();
        return "换一种说法".equals(t);
    }

    /** 2.5.7 Idle 唤模：clientRequestId = idle-task:{taskId}:{terminal}。 */
    static boolean isIdleTaskDelivery(String clientRequestId) {
        return clientRequestId != null && clientRequestId.startsWith("idle-task:");
    }
}
