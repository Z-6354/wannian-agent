package com.wannian.server.app.stream;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.http.TurnToolCallProjector;
import com.wannian.server.app.manage.AgentBudgetSettings;
import com.wannian.server.app.memory.MemoryReviewTurnHooks;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.app.persistence.SqliteTurnQueue;
import com.wannian.server.app.prompt.CompanionPromptService;
import com.wannian.server.app.notice.NoticeCenter;
import com.wannian.server.app.task.IdleDeliveryWorker;
import com.wannian.server.app.task.TaskDeliveryService;
import com.wannian.server.app.title.ConversationAutoTitleService;
import com.wannian.server.kernel.agent.AgentActivityListener;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.prompt.CrisisRiskPolicy;
import com.wannian.server.kernel.task.IdleDeliveryPending;
import com.wannian.server.kernel.task.IdleDeliveryPendingRepository;
import com.wannian.server.kernel.task.IdleDeliveryStatus;
import com.wannian.server.kernel.turn.ExecuteTurn;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import com.wannian.server.kernel.turn.TurnRunListener;
import com.wannian.server.kernel.turn.TurnTerminalWriter;
import com.wannian.server.kernel.persona.ConversationPersonaBinding;
import com.wannian.server.kernel.persona.PersonaTurnSnapshot;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Durable Turn 调度：真源为 SQLite RECEIVED；内存队列仅作唤醒。
 * 同会话最多一个 RUNNING（由 {@link SqliteTurnQueue#pollNextRunnable} 保证）。
 *
 * <p>启动时先 reconcile 孤儿 CLAIMED/RUNNING（标 FAILED + Outbox）与 COMMITTING（恢复提交），
 * 再扫描 RECEIVED；周期扫描过期 lease。
 */
@Component
public class DurableTurnScheduler implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DurableTurnScheduler.class);
    private static final Duration POLL_IDLE = Duration.ofMillis(750);
    private static final Duration RECONCILE_EVERY = Duration.ofSeconds(30);
    /** claim lease 须覆盖 hard deadline，否则长流式成功后 freeze 会 CLAIM_EXPIRED。 */
    private static final Duration CLAIM_LEASE_PAD = Duration.ofSeconds(30);

    private final SqliteTurnQueue queue;
    private final TurnRepository turns;
    private final TurnEngine turnEngine;
    private final TurnTerminalWriter terminalWriter;
    private final EnabledModelPortResolver modelPorts;
    private final AgentBudgetSettings budgetSettings;
    private final CompanionPromptService companionPromptService;
    private final ConversationAutoTitleService autoTitle;
    private final RunEventBus eventBus;
    private final ActiveTurnRegistry activeTurns;
    private ConversationPersonaBinding personaBindings;
    private MemoryReviewTurnHooks memoryReviewTurnHooks;
    private IdleDeliveryPendingRepository idleDeliveries;
    private TaskDeliveryService taskDeliveryService;
    private NoticeCenter noticeCenter;

    @Autowired public void setPersonaBindings(ConversationPersonaBinding personaBindings) { this.personaBindings = personaBindings; }
    @Autowired public void setMemoryReviewTurnHooks(MemoryReviewTurnHooks hooks) { this.memoryReviewTurnHooks = hooks; }
    @Autowired
    public void setIdleDeliveries(IdleDeliveryPendingRepository idleDeliveries) {
        this.idleDeliveries = idleDeliveries;
    }

    @Autowired
    public void setTaskDeliveryService(@Lazy TaskDeliveryService taskDeliveryService) {
        this.taskDeliveryService = taskDeliveryService;
    }

    @Autowired
    public void setNoticeCenter(NoticeCenter noticeCenter) {
        this.noticeCenter = noticeCenter;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object wakeLock = new Object();
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private Thread dispatcher;
    private volatile Instant lastPeriodicReconcile = Instant.EPOCH;

    public DurableTurnScheduler(
            SqliteTurnQueue queue,
            TurnRepository turns,
            TurnEngine turnEngine,
            TurnTerminalWriter terminalWriter,
            EnabledModelPortResolver modelPorts,
            AgentBudgetSettings budgetSettings,
            CompanionPromptService companionPromptService,
            ConversationAutoTitleService autoTitle,
            RunEventBus eventBus,
            ActiveTurnRegistry activeTurns) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.turnEngine = Objects.requireNonNull(turnEngine, "turnEngine");
        this.terminalWriter = Objects.requireNonNull(terminalWriter, "terminalWriter");
        this.modelPorts = Objects.requireNonNull(modelPorts, "modelPorts");
        this.budgetSettings = Objects.requireNonNull(budgetSettings, "budgetSettings");
        this.companionPromptService =
                Objects.requireNonNull(companionPromptService, "companionPromptService");
        this.autoTitle = Objects.requireNonNull(autoTitle, "autoTitle");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.activeTurns = Objects.requireNonNull(activeTurns, "activeTurns");
        AtomicInteger seq = new AtomicInteger();
        this.workers =
                new ThreadPoolExecutor(
                        2,
                        2,
                        30L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(64),
                        (ThreadFactory)
                                r -> {
                                    Thread t =
                                            new Thread(
                                                    r, "turn-worker-" + seq.incrementAndGet());
                                    t.setDaemon(true);
                                    return t;
                                },
                        // 禁止 DiscardPolicy：静默丢任务会留下 inFlight 永久卡死该 turn
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 启动门：先 reconcile 再扫 RECEIVED，避免孤儿 CLAIMED/RUNNING/COMMITTING 堵死会话
        reconcileStartup();
        dispatcher =
                new Thread(
                        this::dispatchLoop,
                        "turn-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
        wake();
    }

    /** 异步 receive 后唤醒扫描。 */
    public void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    public Optional<ActiveTurnRegistry.Active> findActive(TurnId turnId) {
        return activeTurns.find(turnId);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        wake();
        workers.shutdownNow();
        if (dispatcher != null) {
            dispatcher.interrupt();
        }
    }

    /** 进程重启：全部 CLAIMED/RUNNING 无内存 owner → FAILED；COMMITTING → 恢复提交。 */
    private void reconcileStartup() {
        Instant now = Instant.now();
        for (TurnId turnId : queue.listClaimedOrRunning()) {
            failOrphanClaim(turnId, now, "startup orphan");
        }
        for (TurnId turnId : queue.listCommitting()) {
            recoverCommitting(turnId);
        }
        reconcileIdleDeliveries();
        lastPeriodicReconcile = Instant.now();
    }

    /** 周期：仅处理 lease 已过期的 CLAIMED/RUNNING。 */
    private void reconcileExpiredClaims() {
        Instant now = Instant.now();
        for (TurnId turnId : queue.listExpiredClaims(now)) {
            failOrphanClaim(turnId, now, "lease expired");
        }
        for (TurnId turnId : queue.listCommitting()) {
            // COMMITTING 无 lease 回退；若仍卡住则再尝试恢复提交（幂等）
            if (activeTurns.find(turnId).isEmpty() && !inFlight.containsKey(turnId.asString())) {
                recoverCommitting(turnId);
            }
        }
        reconcileIdleDeliveries();
        lastPeriodicReconcile = now;
    }

    /**
     * Idle DISPATCHING 收口：无 wake → 回 QUEUED；wake 已 COMPLETED → DELIVERED；
     * wake FAILED/CANCELLED → 递增 attempt 回 QUEUED；wake 仍进行中 → 保持。
     */
    private void reconcileIdleDeliveries() {
        if (idleDeliveries == null) {
            return;
        }
        for (IdleDeliveryPending pending : idleDeliveries.listDispatching()) {
            try {
                if (pending.wakeTurnId() == null) {
                    if (idleDeliveries.casRequeue(pending.deliveryId())) {
                        LOG.info(
                                "Idle DISPATCHING 无 wake_turn，已回 QUEUED delivery={}",
                                pending.deliveryId().asString());
                    }
                    continue;
                }
                TurnId wakeTurnId = pending.wakeTurnId();
                Optional<Turn> found = turns.find(wakeTurnId);
                if (found.isEmpty()) {
                    idleDeliveries.casRequeueByWakeTurn(wakeTurnId);
                    continue;
                }
                switch (found.get().status()) {
                    case COMPLETED -> markIdleDelivered(wakeTurnId, Optional.of(pending));
                    case FAILED, CANCELLED -> resetIdleAfterWakeFailed(wakeTurnId, pending);
                    default -> {
                        // RECEIVED/CLAIMED/RUNNING/COMMITTING：调度器继续
                    }
                }
            } catch (RuntimeException ex) {
                LOG.warn(
                        "reconcileIdleDeliveries 单条失败 delivery={}: {}",
                        pending.deliveryId().asString(),
                        ex.toString());
            }
        }
    }

    private void resetIdleAfterWakeFailed(TurnId wakeTurnId, IdleDeliveryPending pending) {
        String bumped =
                taskDeliveryService != null
                        ? taskDeliveryService.bumpWakeAttemptPayload(pending.payloadJson())
                        : pending.payloadJson();
        if (idleDeliveries.resetAfterWakeFailed(wakeTurnId, bumped)) {
            LOG.info(
                    "Idle wake Turn 已终态失败，已回 QUEUED 并升 attempt delivery={} turn={}",
                    pending.deliveryId().asString(),
                    wakeTurnId.asString());
        }
    }

    private void failOrphanClaim(TurnId turnId, Instant now, String reason) {
        Optional<Turn> found = turns.find(turnId);
        if (found.isEmpty()) {
            return;
        }
        Turn turn = found.get();
        TurnStatus was = turn.status();
        if (was != TurnStatus.CLAIMED && was != TurnStatus.RUNNING) {
            return;
        }
        if (activeTurns.find(turnId).isPresent()) {
            // 进程内仍有 owner，不抢杀
            return;
        }
        long revision = turn.revision();
        try {
            turn.fail(ErrorCodes.CLAIM_EXPIRED, now);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "孤儿认领 fail 领域迁移失败 turn={} reason={}: {}",
                    turnId.asString(),
                    reason,
                    ex.toString());
            return;
        }
        SaveTurnResult saved = terminalWriter.saveFailed(turn, revision, now);
        if (!(saved instanceof SaveTurnResult.Saved)) {
            LOG.warn(
                    "孤儿认领 FAILED+Outbox 落库失败 turn={} reason={} result={}",
                    turnId.asString(),
                    reason,
                    saved.getClass().getSimpleName());
            return;
        }
        LOG.info(
                "孤儿认领已标 FAILED turn={} statusWas={} reason={}",
                turnId.asString(),
                was,
                reason);
        // Idle 唤模 Turn 失败：升 attempt 回 QUEUED，避免 DISPATCHING 饿死且同 clientRequestId 卡在失败 Turn
        if (idleDeliveries != null) {
            idleDeliveries
                    .findByWakeTurnId(turnId)
                    .ifPresent(p -> resetIdleAfterWakeFailed(turnId, p));
        }
        wake();
    }

    private void recoverCommitting(TurnId turnId) {
        if (inFlight.putIfAbsent(turnId.asString(), Boolean.TRUE) != null) {
            return;
        }
        try {
            Instant now = Instant.now();
            AgentBudget budget = budgetSettings.createBudget(now);
            Turn recovering = turns.find(turnId).orElse(null);
            PersonaTurnSnapshot persona = recovering == null || personaBindings == null ? null : personaBindings.resolveForTurn(recovering.conversationId(), turnId);
            ExecuteTurnResult result =
                    turnEngine.execute(
                            new ExecuteTurn(
                                    turnId,
                                    "",
                                    persona == null ? companionPromptService.composeSystemInstructions() : companionPromptService.composeSystemInstructions(persona),
                                    budget,
                                    claimLease(),
                                    persona));
            if (result instanceof ExecuteTurnResult.Held held) {
                LOG.warn(
                        "COMMITTING 恢复 Held turn={} code={} detail={}",
                        turnId.asString(),
                        held.code(),
                        held.detail());
            } else if (result instanceof ExecuteTurnResult.Replied
                    || result instanceof ExecuteTurnResult.AlreadyCompleted) {
                Optional<Turn> done = turns.find(turnId);
                done.ifPresent(t -> {
                    autoTitle.scheduleAfterCompleted(t.conversationId(), turnId);
                    if (memoryReviewTurnHooks != null) memoryReviewTurnHooks.afterTurnCompleted(t.conversationId(), persona==null?com.wannian.server.kernel.memory.CompanionIdentity.YANHUO:persona.companionIdentity());
                });
                markIdleDelivered(turnId, Optional.empty());
                LOG.info(
                        "COMMITTING 恢复完成 turn={} result={}",
                        turnId.asString(),
                        result.getClass().getSimpleName());
            } else {
                LOG.info(
                        "COMMITTING 恢复完成 turn={} result={}",
                        turnId.asString(),
                        result.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            LOG.warn("COMMITTING 恢复异常 turn={}: {}", turnId.asString(), ex.toString());
        } finally {
            inFlight.remove(turnId.asString());
            wake();
        }
    }

    private void dispatchLoop() {
        while (running.get()) {
            try {
                maybePeriodicReconcile();
                Boolean[] modelEnabled = {null};
                Optional<SqliteTurnQueue.QueuedTurn> next =
                        queue.pollNextRunnable(
                                item -> {
                                    if (modelEnabled[0] == null) {
                                        modelEnabled[0] =
                                                modelPorts.resolve() instanceof ResolveResult.Resolved;
                                    }
                                    return modelEnabled[0] || isRunnableWithoutModel(item);
                                });
                if (next.isEmpty()) {
                    waitIdle();
                    continue;
                }
                SqliteTurnQueue.QueuedTurn item = next.get();
                if (inFlight.putIfAbsent(item.turnId().asString(), Boolean.TRUE) != null) {
                    waitIdle();
                    continue;
                }
                try {
                    workers.execute(() -> executeOne(item));
                } catch (RuntimeException ex) {
                    inFlight.remove(item.turnId().asString());
                    LOG.warn(
                            "提交 Worker 失败 turn={}: {}",
                            item.turnId().asString(),
                            ex.toString());
                    waitIdle();
                }
            } catch (RuntimeException ex) {
                LOG.warn("调度循环异常: {}", ex.toString());
                waitIdle();
            }
        }
    }

    private void maybePeriodicReconcile() {
        Instant now = Instant.now();
        if (now.isBefore(lastPeriodicReconcile.plus(RECONCILE_EVERY))) {
            return;
        }
        try {
            reconcileExpiredClaims();
        } catch (RuntimeException ex) {
            LOG.warn("周期 reconcile 异常: {}", ex.toString());
            lastPeriodicReconcile = now;
        }
    }

    private boolean isRunnableWithoutModel(SqliteTurnQueue.QueuedTurn item) {
        Optional<String> text = queue.loadUserText(item.turnId());
        // Poison rows must reach executeOne so it can close them instead of blocking the queue.
        return text.isEmpty()
                || text.get().isBlank()
                || CrisisRiskPolicy.classify(text.get()).requiresSafetyPath();
    }

    private void waitIdle() {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(POLL_IDLE.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void executeOne(SqliteTurnQueue.QueuedTurn item) {
        try {
            Optional<Turn> found = turns.find(item.turnId());
            if (found.isEmpty()) {
                return;
            }
            TurnStatus status = found.get().status();
            if (status != TurnStatus.RECEIVED) {
                // 崩溃后可能已 COMPLETED/FAILED：仍须收口 Idle 行，避免永久 DISPATCHING
                if (status == TurnStatus.COMPLETED) {
                    markIdleDelivered(item.turnId(), Optional.empty());
                } else if (status == TurnStatus.FAILED || status == TurnStatus.CANCELLED) {
                    if (idleDeliveries != null) {
                        idleDeliveries
                                .findByWakeTurnId(item.turnId())
                                .ifPresent(p -> resetIdleAfterWakeFailed(item.turnId(), p));
                    }
                }
                return;
            }
            Optional<String> userText = queue.loadUserText(item.turnId());
            if (userText.isEmpty() || userText.get().isBlank()) {
                LOG.warn("待执行回合缺少用户正文，撤队 turn={}", item.turnId().asString());
                failPoisonReceived(found.get());
                cancelIdleWake(item.turnId());
                return;
            }
            // 正文验证后才做危机分类。危机轮由 Loop 走确定性回应，不依赖已启用模型。
            if (!CrisisRiskPolicy.classify(userText.get()).requiresSafetyPath()
                    && !(modelPorts.resolve() instanceof ResolveResult.Resolved)) {
                return;
            }
            AgentBudget.CancelToken cancelToken = new AgentBudget.CancelToken();
            PersonaTurnSnapshot persona = personaBindings == null ? null : personaBindings.resolveForTurn(item.conversationId(), item.turnId());
            Instant now = Instant.now();
            AgentBudget base = budgetSettings.createBudget(now);
            Optional<IdleDeliveryPending> idleWake =
                    idleDeliveries == null
                            ? Optional.empty()
                            : idleDeliveries.findByWakeTurnId(item.turnId());
            boolean idleDelivery =
                    idleWake.isPresent()
                            || (found.get().clientRequestId() != null
                                    && found.get()
                                            .clientRequestId()
                                            .startsWith(IdleDeliveryWorker.CLIENT_REQUEST_PREFIX));
            AgentBudget budget =
                    idleDelivery
                            ? new AgentBudget(
                                    Math.min(4, base.maxModelDecisions()),
                                    base.maxSystemToolInvocationsPerTool(),
                                    now.plusSeconds(45),
                                    now.plusSeconds(90),
                                    cancelToken)
                            : new AgentBudget(
                                    base.maxModelDecisions(),
                                    base.maxSystemToolInvocationsPerTool(),
                                    base.softDeadline(),
                                    base.hardDeadline(),
                                    cancelToken);
            String system =
                    persona == null
                            ? companionPromptService.composeSystemInstructions()
                            : companionPromptService.composeSystemInstructions(persona);
            if (idleWake.isPresent()) {
                system = system + "\n\n" + IdleDeliveryWorker.formatIdleReportBlock(idleWake.get());
            }
            TurnRunContext.Handle handle =
                    new TurnRunContext.Handle(
                            item.conversationId(), item.turnId(), cancelToken, eventBus);
            TurnRunContext.enter(handle);
            activeTurns.register(
                    item.turnId(),
                    item.conversationId(),
                    cancelToken,
                    Thread.currentThread());
            try {
                ExecuteTurnResult result =
                        turnEngine.execute(
                                new ExecuteTurn(
                                        item.turnId(),
                                        userText.get(),
                                        system,
                                        budget,
                                        claimLease(),
                                        persona));
                if (result instanceof ExecuteTurnResult.Replied) {
                    autoTitle.scheduleAfterCompleted(item.conversationId(), item.turnId());
                    if (memoryReviewTurnHooks != null) memoryReviewTurnHooks.afterTurnCompleted(item.conversationId(), persona==null?com.wannian.server.kernel.memory.CompanionIdentity.YANHUO:persona.companionIdentity());
                    markIdleDelivered(item.turnId(), idleWake);
                } else if (result instanceof ExecuteTurnResult.AlreadyCompleted) {
                    markIdleDelivered(item.turnId(), idleWake);
                } else if (result instanceof ExecuteTurnResult.Held held) {
                    LOG.info(
                            "异步执行 Held turn={} code={}",
                            item.turnId().asString(),
                            held.code());
                    handleIdleExecuteOutcome(item.turnId(), idleWake);
                }
            } catch (RuntimeException ex) {
                LOG.warn("异步执行异常 turn={}: {}", item.turnId().asString(), ex.toString());
                handleIdleExecuteOutcome(item.turnId(), idleWake);
            } finally {
                activeTurns.unregister(item.turnId());
                TurnRunContext.clear();
            }
        } finally {
            // 无论早退、丢弃恢复或执行结束，都必须释放 inFlight，否则该 turn 永久不可调度
            inFlight.remove(item.turnId().asString());
            wake();
        }
    }

    /** claim lease ≥ hard deadline，避免长流式成功后因 lease 过期无法 freeze。 */
    private Duration claimLease() {
        int hard = budgetSettings.snapshot().hardDeadlineSeconds();
        return Duration.ofSeconds(hard).plus(CLAIM_LEASE_PAD);
    }

    /** 无法解析用户正文的 RECEIVED：CANCELLED + Outbox，避免毒丸堵死全局调度。 */
    private void failPoisonReceived(Turn turn) {
        if (turn.status() != TurnStatus.RECEIVED) {
            return;
        }
        Instant now = Instant.now();
        long revision = turn.revision();
        try {
            turn.cancel(now);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "毒丸回合 cancel 失败 turn={}: {}", turn.id().asString(), ex.toString());
            return;
        }
        SaveTurnResult saved = terminalWriter.saveCancelled(turn, revision, now);
        if (!(saved instanceof SaveTurnResult.Saved)) {
            LOG.warn(
                    "毒丸回合 CANCELLED+Outbox 落库失败 turn={} result={}",
                    turn.id().asString(),
                    saved.getClass().getSimpleName());
        }
    }

    private void markIdleDelivered(TurnId turnId, Optional<IdleDeliveryPending> idleWake) {
        if (idleDeliveries == null) {
            return;
        }
        IdleDeliveryPending pending =
                idleWake.orElseGet(
                        () -> idleDeliveries.findByWakeTurnId(turnId).orElse(null));
        if (pending == null) {
            return;
        }
        if (pending.status() == IdleDeliveryStatus.DELIVERED) {
            return;
        }
        boolean ok = idleDeliveries.casDelivered(pending.deliveryId(), turnId, Instant.now());
        if (!ok) {
            LOG.warn(
                    "Idle casDelivered 未生效 delivery={} turn={} statusWas={}",
                    pending.deliveryId().asString(),
                    turnId.asString(),
                    pending.status());
            return;
        }
        publishIdleNotice(pending);
    }

    private void publishIdleNotice(IdleDeliveryPending pending) {
        if (noticeCenter == null) {
            return;
        }
        try {
            String errorCode = null;
            String preview = null;
            boolean scheduled = false;
            String payload = pending.payloadJson();
            if (payload != null && !payload.isBlank()) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                    if (node.hasNonNull("errorCode")) {
                        errorCode = node.get("errorCode").asText();
                    }
                    if (node.hasNonNull("resultPreview")) {
                        preview = node.get("resultPreview").asText();
                    }
                    scheduled = node.path("scheduled").asBoolean(false);
                } catch (Exception ignored) {
                    // payload 解析失败仍发通知
                }
            }
            noticeCenter.publishTaskTerminal(
                    pending.conversationId(),
                    pending.taskId(),
                    pending.terminalStatus(),
                    errorCode,
                    preview,
                    scheduled);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Idle 消息中心 publish 失败 delivery={}: {}",
                    pending.deliveryId().asString(),
                    ex.toString());
        }
    }

    /**
     * Idle 唤模失败收口：Turn 仍可执行则保持 DISPATCHING（保留 wake_turn，避免丢汇报块/死循环）；
     * 已 COMPLETED 则交付；FAILED/CANCELLED 则取消 Idle 行。
     */
    private void handleIdleExecuteOutcome(
            TurnId turnId, Optional<IdleDeliveryPending> idleWake) {
        if (idleDeliveries == null) {
            return;
        }
        if (idleWake.isEmpty() && idleDeliveries.findByWakeTurnId(turnId).isEmpty()) {
            return;
        }
        Optional<Turn> latest = turns.find(turnId);
        if (latest.isEmpty()) {
            cancelIdleWake(turnId);
            return;
        }
        switch (latest.get().status()) {
            case COMPLETED -> markIdleDelivered(turnId, idleWake);
            case RECEIVED, CLAIMED, RUNNING, COMMITTING -> {
                // 保持 DISPATCHING + wake_turn；调度器后续重试同一 Turn
            }
            case FAILED, CANCELLED -> {
                IdleDeliveryPending pending =
                        idleWake.orElseGet(
                                () -> idleDeliveries.findByWakeTurnId(turnId).orElse(null));
                if (pending != null) {
                    resetIdleAfterWakeFailed(turnId, pending);
                } else {
                    cancelIdleWake(turnId);
                }
            }
            default -> cancelIdleWake(turnId);
        }
    }

    private void cancelIdleWake(TurnId turnId) {
        if (idleDeliveries == null) {
            return;
        }
        try {
            idleDeliveries.casCancelledByWakeTurn(turnId);
        } catch (RuntimeException ex) {
            LOG.warn("Idle 取消失败 turn={}: {}", turnId.asString(), ex.toString());
        }
    }

    /** 运行中 Turn 登记，供 Stop（异步 Worker 与同步 TurnController 共用）。 */
    @Component
    public static class ActiveTurnRegistry {
        private final ConcurrentHashMap<String, Active> byTurn = new ConcurrentHashMap<>();

        public void register(
                TurnId turnId,
                com.wannian.server.api.common.ConversationId conversationId,
                AgentBudget.CancelToken token,
                Thread worker) {
            byTurn.put(
                    turnId.asString(),
                    new Active(turnId, conversationId, token, worker));
        }

        public void unregister(TurnId turnId) {
            byTurn.remove(turnId.asString());
        }

        public Optional<Active> find(TurnId turnId) {
            return Optional.ofNullable(byTurn.get(turnId.asString()));
        }

        public record Active(
                TurnId turnId,
                com.wannian.server.api.common.ConversationId conversationId,
                AgentBudget.CancelToken cancelToken,
                Thread worker) {}
    }

    /** 把 Loop 工具活动接到 RunEventBus；参数经 {@link TurnToolCallProjector} 脱敏。 */
    @Component
    public static class StreamingActivityListener implements AgentActivityListener {
        private final TurnToolCallProjector projector;

        public StreamingActivityListener(TurnToolCallProjector projector) {
            this.projector = Objects.requireNonNull(projector, "projector");
        }

        @Override
        public void onToolStarted(
                com.wannian.server.api.common.ConversationId conversationId,
                TurnId turnId,
                String executionHint,
                String callId,
                String operationId,
                String toolName,
                String rawArgumentsJson) {
            String safe =
                    projector.safeArgumentsJson(
                            toolName,
                            callId,
                            operationId,
                            rawArgumentsJson == null || rawArgumentsJson.isBlank()
                                    ? "{}"
                                    : rawArgumentsJson);
            TurnRunContext.current()
                    .ifPresent(
                            h ->
                                    h.publishToolStarted(
                                            callId, operationId, toolName, safe));
        }

        @Override
        public void onToolUpdated(
                com.wannian.server.api.common.ConversationId conversationId,
                TurnId turnId,
                String executionHint,
                String callId,
                String operationId,
                String toolName,
                String status,
                String errorCode,
                String safeResultSummary) {
            TurnRunContext.current()
                    .ifPresent(
                            h ->
                                    h.publishToolUpdated(
                                            callId,
                                            operationId,
                                            toolName,
                                            status,
                                            errorCode,
                                            safeResultSummary));
        }
    }

    /** RUNNING 钩子：回填 executionId 并发 turn.started。 */
    @Component
    public static class StreamingTurnRunListener implements TurnRunListener {
        private final com.wannian.server.kernel.persona.PendingConversationPersonaSwitch personaSwitches;
        private final com.wannian.server.app.task.TaskDeliveryService taskDeliveryService;

        public StreamingTurnRunListener(
                com.wannian.server.kernel.persona.PendingConversationPersonaSwitch personaSwitches,
                @org.springframework.context.annotation.Lazy
                        com.wannian.server.app.task.TaskDeliveryService taskDeliveryService) {
            this.personaSwitches = personaSwitches;
            this.taskDeliveryService = taskDeliveryService;
        }

        @Override
        public void onRunning(
                com.wannian.server.api.common.ConversationId conversationId,
                TurnId turnId,
                String executionId) {
            TurnRunContext.current()
                    .ifPresent(
                            h -> {
                                h.updateExecutionId(executionId);
                                h.publishTurnStarted();
                            });
        }

        @Override public void onCompleted(com.wannian.server.api.common.ConversationId conversationId,TurnId turnId,String executionId) {
            personaSwitches.turnCompleted(conversationId,turnId);
            try {
                taskDeliveryService.flushAfterTurnCompleted(conversationId, turnId);
            } catch (RuntimeException ignored) {
                // Busy 交付不得打断 Turn 完成钩
            }
        }

        @Override public void onAborted(com.wannian.server.api.common.ConversationId conversationId,TurnId turnId,String executionId) {
            personaSwitches.turnAborted(conversationId,turnId);
        }

        @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
        public void recoverPendingPersonaSwitches() {
            personaSwitches.recoverTerminalTurns();
        }
    }
}
