package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.task.BackgroundConcurrencyGate;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.CancelTaskResult;
import com.wannian.server.kernel.task.CompletionOutcome;
import com.wannian.server.kernel.task.DispatchBudget;
import com.wannian.server.kernel.task.DispatchOutcome;
import com.wannian.server.kernel.task.DispatchResult;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.ResolvedSchedule;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.SubAgentRunResult;
import com.wannian.server.kernel.task.SubAgentRunSnapshot;
import com.wannian.server.kernel.task.SubAgentRunSpec;
import com.wannian.server.kernel.task.SubAgentRunStatus;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskExecutor;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaskRuntime}：prepare（2.5.2）+ dispatch/accept/cancel（2.5.4）。
 *
 * <p><b>F13 已钉：</b>{@code executor.deadline + 30s ≤ lease-duration}（默认 4m+30s ≤ 5m），
 * 避免同步执行中被 reclaim 误标 LOST。
 */
public final class DefaultTaskRuntime implements TaskRuntime {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    public static final String DEFAULT_RETRY_POLICY_JSON = "{\"maxAttempts\":2}";
    public static final int MAX_INPUT_CHARS = 256 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultTaskRuntime.class);

    private final ScheduleResolver scheduleResolver;
    private final Clock clock;
    private final DataSource dataSource;
    private final BackgroundTaskRepository backgroundTaskRepository;
    private final SubAgentRunRepository subAgentRunRepository;
    private final TaskExecutor taskExecutor;
    private final BackgroundConcurrencyGate concurrencyGate;
    private final Duration leaseDuration;
    private final Duration executorDeadline;
    private final int defaultMaxAttempts;
    private final ObjectMapper objectMapper;
    /** 2.5.6：可空；Busy 虚线交付钩。 */
    private final TaskDeliveryService taskDeliveryService;

    public DefaultTaskRuntime(ScheduleResolver scheduleResolver) {
        this(scheduleResolver, Clock.systemUTC());
    }

    /** 2.5.2 兼容：仅 prepare。 */
    public DefaultTaskRuntime(ScheduleResolver scheduleResolver, Clock clock) {
        this.scheduleResolver = Objects.requireNonNull(scheduleResolver, "scheduleResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dataSource = null;
        this.backgroundTaskRepository = null;
        this.subAgentRunRepository = null;
        this.taskExecutor = null;
        this.concurrencyGate = null;
        this.leaseDuration = Duration.ofMinutes(5);
        this.executorDeadline = Duration.ofMinutes(4);
        this.defaultMaxAttempts = 2;
        this.objectMapper = new ObjectMapper();
        this.taskDeliveryService = null;
    }

    public DefaultTaskRuntime(
            ScheduleResolver scheduleResolver,
            Clock clock,
            DataSource dataSource,
            BackgroundTaskRepository backgroundTaskRepository,
            SubAgentRunRepository subAgentRunRepository,
            TaskExecutor taskExecutor,
            BackgroundConcurrencyGate concurrencyGate,
            Duration leaseDuration,
            Duration executorDeadline,
            int defaultMaxAttempts,
            ObjectMapper objectMapper) {
        this(
                scheduleResolver,
                clock,
                dataSource,
                backgroundTaskRepository,
                subAgentRunRepository,
                taskExecutor,
                concurrencyGate,
                leaseDuration,
                executorDeadline,
                defaultMaxAttempts,
                objectMapper,
                null);
    }

    public DefaultTaskRuntime(
            ScheduleResolver scheduleResolver,
            Clock clock,
            DataSource dataSource,
            BackgroundTaskRepository backgroundTaskRepository,
            SubAgentRunRepository subAgentRunRepository,
            TaskExecutor taskExecutor,
            BackgroundConcurrencyGate concurrencyGate,
            Duration leaseDuration,
            Duration executorDeadline,
            int defaultMaxAttempts,
            ObjectMapper objectMapper,
            TaskDeliveryService taskDeliveryService) {
        this.scheduleResolver = Objects.requireNonNull(scheduleResolver, "scheduleResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.backgroundTaskRepository =
                Objects.requireNonNull(backgroundTaskRepository, "backgroundTaskRepository");
        this.subAgentRunRepository =
                Objects.requireNonNull(subAgentRunRepository, "subAgentRunRepository");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.concurrencyGate = Objects.requireNonNull(concurrencyGate, "concurrencyGate");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.executorDeadline = Objects.requireNonNull(executorDeadline, "executorDeadline");
        if (leaseDuration.compareTo(executorDeadline.plusSeconds(30)) < 0) {
            throw new IllegalArgumentException(
                    "F13：lease-duration 须 ≥ executor.deadline + 30s（已钉）");
        }
        if (defaultMaxAttempts < 1) {
            throw new IllegalArgumentException("defaultMaxAttempts 须 ≥ 1");
        }
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.taskDeliveryService = taskDeliveryService;
    }

    @Override
    public TaskDraft prepare(TaskProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        OriginTurn origin = proposal.origin();

        if (proposal.source() != TaskSource.USER_LOOP) {
            throw new IllegalArgumentException("prepare 仅允许 source=USER_LOOP");
        }
        TaskType type = proposal.taskType();
        if (type == TaskType.WORLD_TICK || type == TaskType.MEMORY_REVIEW) {
            throw new IllegalArgumentException("prepare 拒绝 taskType=" + type);
        }

        ScheduleSpec spec = proposal.scheduleSpec();
        if (type == TaskType.USER_SCHEDULED_NOTIFY && spec == null) {
            throw new IllegalArgumentException("USER_SCHEDULED_NOTIFY 须带定时 scheduleSpec");
        }

        String inputJson = proposal.inputJson();
        if (inputJson.isBlank()) {
            throw new IllegalArgumentException("inputJson 不得为空");
        }
        if (type == TaskType.USER_SCHEDULED_NOTIFY && !NotifyInput.hasNotifyBody(inputJson)) {
            throw new IllegalArgumentException(
                    "USER_SCHEDULED_NOTIFY 须含 message 或 reminder 正文");
        }
        if (inputJson.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "inputJson 超过上限 " + MAX_INPUT_CHARS + " chars（D2）");
        }

        NotifyPolicy notify =
                proposal.notifyPolicy() != null
                        ? proposal.notifyPolicy()
                        : NotifyPolicy.USER_VISIBLE;

        String retry =
                proposal.retryPolicyJson() == null || proposal.retryPolicyJson().isBlank()
                        ? DEFAULT_RETRY_POLICY_JSON
                        : proposal.retryPolicyJson();

        Instant now = clock.instant();
        ResolvedSchedule resolved = scheduleResolver.resolve(spec, DEFAULT_TIMEZONE, now);

        BackgroundTaskStatus initial =
                resolved.isImmediate()
                        ? BackgroundTaskStatus.CREATED
                        : BackgroundTaskStatus.SCHEDULED;

        return new TaskDraft(
                BackgroundTaskId.generate(),
                origin.conversationId(),
                origin.turnId(),
                origin.companionId(),
                proposal.source(),
                type,
                notify,
                inputJson,
                retry,
                resolved.scheduleSpec(),
                DEFAULT_TIMEZONE,
                resolved.nextFireAt(),
                initial);
    }

    @Override
    public DispatchOutcome dispatchNext(DispatchBudget budget) {
        Objects.requireNonNull(budget, "budget");
        requireDispatchWired();
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<ReclaimFailed> reclaimFailed = reclaimExpiredLeases(connection, now);
                connection.commit();
                for (ReclaimFailed f : reclaimFailed) {
                    notifyDelivery(
                            f.taskId(),
                            BackgroundTaskStatus.FAILED,
                            null,
                            ErrorCodes.CLAIM_EXPIRED,
                            f.runId());
                }
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("reclaim 失败", ex);
        }

        if (!concurrencyGate.canStartBackgroundRun() || budget.maxBackgroundRuns() < 1) {
            return new DispatchOutcome.Deferred("concurrency gate");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<TaskDraft> claimed =
                        backgroundTaskRepository.claimNextExecutable(connection, now);
                if (claimed.isEmpty()) {
                    connection.commit();
                    return new DispatchOutcome.NoneReady();
                }
                TaskDraft task = claimed.get();
                int attemptNo = nextAttemptNo(task.taskId());
                SubAgentRunId runId = SubAgentRunId.generate();
                String leaseToken = UUID.randomUUID().toString();
                String tokenHash = sha256Hex(leaseToken);
                Instant leaseExpires = now.plus(leaseDuration);

                subAgentRunRepository.insertCreated(
                        connection, task.taskId(), runId, attemptNo, now);
                subAgentRunRepository.attachLease(
                        connection, runId, tokenHash, leaseExpires, now);
                connection.commit();

                SubAgentRunSpec spec =
                        new SubAgentRunSpec(
                                task.taskId(),
                                runId,
                                attemptNo,
                                leaseToken,
                                SubAgentRunSpec.LOCAL_EXECUTOR_ID,
                                task.taskType(),
                                task.inputJson(),
                                task.conversationId(),
                                task.companionId());

                DispatchResult dispatched = taskExecutor.dispatch(spec);
                if (dispatched instanceof DispatchResult.Rejected rejected) {
                    acceptExecutorFailure(
                            task,
                            runId,
                            attemptNo,
                            leaseToken,
                            ErrorCodes.ILLEGAL_ARGUMENT,
                            rejected.reason());
                    return new DispatchOutcome.Deferred("executor rejected: " + rejected.reason());
                }

                if (dispatched instanceof DispatchResult.Accepted accepted
                        && accepted.result() != null) {
                    acceptResult(accepted.result());
                }
                return new DispatchOutcome.Dispatched(task.taskId(), runId);
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("dispatchNext 失败", ex);
        }
    }

    @Override
    public CompletionOutcome acceptResult(SubAgentRunResult result) {
        Objects.requireNonNull(result, "result");
        requireDispatchWired();
        Instant now = clock.instant();
        Optional<SubAgentRunSnapshot> runOpt = subAgentRunRepository.findById(result.runId());
        if (runOpt.isEmpty()) {
            return new CompletionOutcome.Rejected("run not found");
        }
        SubAgentRunSnapshot run = runOpt.get();
        if (run.status() != SubAgentRunStatus.LEASED && run.status() != SubAgentRunStatus.RUNNING) {
            return new CompletionOutcome.Ignored("run already terminal: " + run.status());
        }
        if (run.attemptNo() != result.attemptNo()) {
            return new CompletionOutcome.Rejected("attempt mismatch");
        }
        String hash = sha256Hex(result.leaseToken());
        if (run.leaseTokenHash() == null || !run.leaseTokenHash().equals(hash)) {
            return new CompletionOutcome.Rejected("lease token mismatch");
        }
        if (!run.taskId().equals(result.taskId())) {
            return new CompletionOutcome.Rejected("task mismatch");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (result.succeeded()) {
                    String json = result.resultJson();
                    if (json == null || json.isBlank()) {
                        return failInvalid(connection, run, now, "成功结果缺少 result_json");
                    }
                    boolean runOk =
                            subAgentRunRepository.casStatusWithTokenHash(
                                    connection,
                                    run.runId(),
                                    hash,
                                    SubAgentRunStatus.RUNNING,
                                    SubAgentRunStatus.SUCCEEDED,
                                    now);
                    if (!runOk) {
                        connection.rollback();
                        return new CompletionOutcome.Ignored("run CAS lost");
                    }
                    subAgentRunRepository.writeTerminal(
                            connection,
                            run.runId(),
                            SubAgentRunStatus.SUCCEEDED,
                            json,
                            null,
                            now);
                    Optional<TaskDraft> taskForSchedule =
                            backgroundTaskRepository.findById(run.taskId());
                    Optional<Instant> nextFire = Optional.empty();
                    if (taskForSchedule.isPresent()) {
                        TaskDraft task = taskForSchedule.get();
                        ScheduleSpec spec = task.scheduleSpec();
                        if (spec != null && spec.recurring()) {
                            nextFire = scheduleResolver.advance(spec, task.timezone(), now);
                        }
                    }
                    if (nextFire.isPresent()) {
                        // 2.5.8 S3：多次 → 回 SCHEDULED，交付仍报本次 SUCCEEDED
                        boolean rescheduled =
                                backgroundTaskRepository.rescheduleAfterSuccessfulFire(
                                        connection, run.taskId(), nextFire.get(), now);
                        if (!rescheduled) {
                            connection.rollback();
                            return new CompletionOutcome.Ignored("reschedule CAS lost");
                        }
                    } else {
                        backgroundTaskRepository.writeResult(
                                connection,
                                run.taskId(),
                                json,
                                BackgroundTaskStatus.SUCCEEDED,
                                now);
                    }
                    TaskDraft deliveryTask = taskForSchedule.orElse(null);
                    recordDeliveryIntent(
                            connection,
                            deliveryTask,
                            BackgroundTaskStatus.SUCCEEDED,
                            json,
                            null,
                            run.runId());
                    connection.commit();
                    afterDeliveryCommit(
                            deliveryTask,
                            BackgroundTaskStatus.SUCCEEDED,
                            json,
                            null);
                    return new CompletionOutcome.Accepted(run.taskId(), run.runId());
                }

                String code =
                        ErrorCodes.isRegistered(result.errorCode())
                                ? result.errorCode()
                                : ErrorCodes.TASK_RESULT_INVALID;
                subAgentRunRepository.writeTerminal(
                        connection,
                        run.runId(),
                        SubAgentRunStatus.FAILED,
                        null,
                        code,
                        now);
                Optional<TaskDraft> taskOpt = backgroundTaskRepository.findById(run.taskId());
                int maxAttempts =
                        taskOpt.map(t -> parseMaxAttempts(t.retryPolicyJson())).orElse(defaultMaxAttempts);
                if (run.attemptNo() < maxAttempts && isRetryable(code)) {
                    backgroundTaskRepository.casStatus(
                            connection,
                            run.taskId(),
                            BackgroundTaskStatus.RUNNING,
                            BackgroundTaskStatus.READY,
                            now);
                } else {
                    backgroundTaskRepository.writeResult(
                            connection,
                            run.taskId(),
                            null,
                            BackgroundTaskStatus.FAILED,
                            now);
                    TaskDraft deliveryTask = taskOpt.orElse(null);
                    recordDeliveryIntent(
                            connection,
                            deliveryTask,
                            BackgroundTaskStatus.FAILED,
                            null,
                            code,
                            run.runId());
                    connection.commit();
                    afterDeliveryCommit(
                            deliveryTask, BackgroundTaskStatus.FAILED, null, code);
                    return new CompletionOutcome.Accepted(run.taskId(), run.runId());
                }
                connection.commit();
                return new CompletionOutcome.Accepted(run.taskId(), run.runId());
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("acceptResult 失败", ex);
        }
    }

    private void recordDeliveryIntent(
            Connection connection,
            TaskDraft task,
            BackgroundTaskStatus terminal,
            String resultJson,
            String errorCode,
            SubAgentRunId runId) {
        if (taskDeliveryService == null || task == null) {
            return;
        }
        try {
            taskDeliveryService.recordDeliveryIntentOn(
                    connection,
                    task,
                    terminal,
                    resultJson,
                    errorCode,
                    runId == null ? null : runId.asString());
        } catch (SQLException ex) {
            throw new IllegalStateException("recordDeliveryIntent 失败", ex);
        }
    }

    private void afterDeliveryCommit(
            TaskDraft task,
            BackgroundTaskStatus terminal,
            String resultJson,
            String errorCode) {
        if (taskDeliveryService == null || task == null) {
            return;
        }
        try {
            taskDeliveryService.afterDeliveryCommit(task, terminal, resultJson, errorCode);
        } catch (RuntimeException ex) {
            LOG.error(
                    "afterDeliveryCommit 失败 task={} terminal={}",
                    task.taskId().asString(),
                    terminal,
                    ex);
        }
    }

    private void notifyDelivery(
            BackgroundTaskId taskId,
            BackgroundTaskStatus terminal,
            String resultJson,
            String errorCode) {
        notifyDelivery(taskId, terminal, resultJson, errorCode, null);
    }

    private void notifyDelivery(
            BackgroundTaskId taskId,
            BackgroundTaskStatus terminal,
            String resultJson,
            String errorCode,
            SubAgentRunId runId) {
        if (taskDeliveryService == null) {
            return;
        }
        Optional<TaskDraft> task = backgroundTaskRepository.findById(taskId);
        if (task.isEmpty()) {
            return;
        }
        String runIdText = runId == null ? null : runId.asString();
        RuntimeException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                taskDeliveryService.onTaskTerminal(
                        task.get(), terminal, resultJson, errorCode, runIdText);
                return;
            } catch (RuntimeException ex) {
                first = ex;
                LOG.error(
                        "notifyDelivery 失败 task={} terminal={} attempt={}",
                        taskId.asString(),
                        terminal,
                        attempt + 1,
                        ex);
            }
        }
        if (first != null) {
            LOG.error(
                    "notifyDelivery 放弃 task={} terminal={}",
                    taskId.asString(),
                    terminal);
        }
    }

    @Override
    public CancelTaskResult requestCancel(BackgroundTaskId id) {
        Objects.requireNonNull(id, "id");
        requireDispatchWired();
        Instant now = clock.instant();
        Optional<TaskDraft> taskOpt = backgroundTaskRepository.findById(id);
        if (taskOpt.isEmpty()) {
            return new CancelTaskResult.NotFound(id);
        }
        BackgroundTaskStatus status = taskOpt.get().initialStatus();
        if (status == BackgroundTaskStatus.SUCCEEDED
                || status == BackgroundTaskStatus.FAILED
                || status == BackgroundTaskStatus.CANCELLED) {
            return new CancelTaskResult.AlreadyTerminal(id, status);
        }

        // 2.5.8 S6：未开火 SCHEDULED 用 CAS，避免与晋升竞态双活
        if (status == BackgroundTaskStatus.SCHEDULED) {
            try (Connection connection = dataSource.getConnection()) {
                if (backgroundTaskRepository.cancelScheduled(connection, id, now)) {
                    notifyDelivery(id, BackgroundTaskStatus.CANCELLED, null, ErrorCodes.CANCELLED);
                    return new CancelTaskResult.Accepted(id, BackgroundTaskStatus.CANCELLED);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("requestCancel SCHEDULED 失败", ex);
            }
            // CAS 丢：可能已晋升，按当前态继续
            taskOpt = backgroundTaskRepository.findById(id);
            if (taskOpt.isEmpty()) {
                return new CancelTaskResult.NotFound(id);
            }
            status = taskOpt.get().initialStatus();
            if (status == BackgroundTaskStatus.CANCELLED) {
                return new CancelTaskResult.AlreadyTerminal(id, status);
            }
        }

        Optional<SubAgentRunId> latest = subAgentRunRepository.findLatestByTaskId(id);
        if (latest.isPresent()) {
            Optional<SubAgentRunSnapshot> snap = subAgentRunRepository.findById(latest.get());
            if (snap.isPresent()
                    && (snap.get().status() == SubAgentRunStatus.RUNNING
                            || snap.get().status() == SubAgentRunStatus.LEASED
                            || snap.get().status() == SubAgentRunStatus.CREATED)) {
                taskExecutor.cancel(latest.get(), "cancel");
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        subAgentRunRepository.writeTerminal(
                                connection,
                                latest.get(),
                                SubAgentRunStatus.CANCELLED,
                                null,
                                ErrorCodes.CANCELLED,
                                now);
                        backgroundTaskRepository.writeResult(
                                connection, id, null, BackgroundTaskStatus.CANCELLED, now);
                        connection.commit();
                    } catch (RuntimeException ex) {
                        connection.rollback();
                        throw ex;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException ex) {
                    throw new IllegalStateException("requestCancel 失败", ex);
                }
                notifyDelivery(id, BackgroundTaskStatus.CANCELLED, null, ErrorCodes.CANCELLED);
                return new CancelTaskResult.Accepted(id, BackgroundTaskStatus.CANCELLED);
            }
        }

        try (Connection connection = dataSource.getConnection()) {
            backgroundTaskRepository.writeResult(
                    connection, id, null, BackgroundTaskStatus.CANCELLED, now);
        } catch (SQLException ex) {
            throw new IllegalStateException("requestCancel 写库失败", ex);
        }
        notifyDelivery(id, BackgroundTaskStatus.CANCELLED, null, ErrorCodes.CANCELLED);
        return new CancelTaskResult.Accepted(id, BackgroundTaskStatus.CANCELLED);
    }

    @Override
    public int promoteDueScheduled(int limit) {
        requireDispatchWired();
        int max = Math.max(1, limit);
        Instant now = clock.instant();
        int promoted = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (int i = 0; i < max; i++) {
                    Optional<TaskDraft> claimed =
                            backgroundTaskRepository.claimDueScheduled(connection, now);
                    if (claimed.isEmpty()) {
                        break;
                    }
                    promoted++;
                    LOG.info(
                            "定时晋升 SCHEDULED→CREATED task={} nextWas={}",
                            claimed.get().taskId().asString(),
                            claimed.get().nextFireAt());
                }
                connection.commit();
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("promoteDueScheduled 失败", ex);
        }
        return promoted;
    }

    /**
     * 回收过期 lease：标记 LOST；orphan RUNNING 按 attempt 预算回 READY 或 FAILED。
     *
     * @return 已写 FAILED、待提交后投递的 (taskId, runId) 列表
     */
    private List<ReclaimFailed> reclaimExpiredLeases(Connection connection, Instant now) {
        int lost = subAgentRunRepository.markLostExpired(connection, now);
        if (lost == 0) {
            return List.of();
        }
        List<ReclaimFailed> failed = new ArrayList<>();
        // E1：orphan RUNNING + 最新 Run=LOST → attemptNo < maxAttempts 则 READY，否则 FAILED
        try (var ps =
                        connection.prepareStatement(
                                """
                                SELECT t.id, t.retry_policy_json, r.attempt_no, r.id AS run_id
                                FROM background_task t
                                JOIN sub_agent_run r ON r.task_id = t.id
                                WHERE t.status = 'RUNNING'
                                  AND t.id NOT IN (
                                    SELECT task_id FROM sub_agent_run
                                    WHERE status IN ('LEASED', 'RUNNING')
                                  )
                                  AND r.status = 'LOST'
                                  AND r.id = (
                                    SELECT r2.id FROM sub_agent_run r2
                                    WHERE r2.task_id = t.id
                                    ORDER BY r2.attempt_no DESC, r2.id DESC
                                    LIMIT 1
                                  )
                                """);
                var rs = ps.executeQuery()) {
            while (rs.next()) {
                BackgroundTaskId taskId = BackgroundTaskId.parse(rs.getString("id"));
                int attemptNo = rs.getInt("attempt_no");
                SubAgentRunId runId = new SubAgentRunId(UUID.fromString(rs.getString("run_id")));
                int maxAttempts = parseMaxAttempts(rs.getString("retry_policy_json"));
                if (attemptNo < maxAttempts) {
                    backgroundTaskRepository.casStatus(
                            connection,
                            taskId,
                            BackgroundTaskStatus.RUNNING,
                            BackgroundTaskStatus.READY,
                            now);
                } else {
                    backgroundTaskRepository.writeResult(
                            connection, taskId, null, BackgroundTaskStatus.FAILED, now);
                    failed.add(new ReclaimFailed(taskId, runId));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("orphan RUNNING 回收失败", ex);
        }
        return failed;
    }

    private record ReclaimFailed(BackgroundTaskId taskId, SubAgentRunId runId) {}

    private int nextAttemptNo(BackgroundTaskId taskId) {
        Optional<SubAgentRunId> latest = subAgentRunRepository.findLatestByTaskId(taskId);
        if (latest.isEmpty()) {
            return 1;
        }
        return subAgentRunRepository
                .findById(latest.get())
                .map(s -> s.attemptNo() + 1)
                .orElse(1);
    }

    private void acceptExecutorFailure(
            TaskDraft task,
            SubAgentRunId runId,
            int attemptNo,
            String leaseToken,
            String code,
            String message) {
        acceptResult(
                SubAgentRunResult.failure(
                        task.taskId(), runId, attemptNo, leaseToken, code, message));
    }

    private CompletionOutcome failInvalid(
            Connection connection, SubAgentRunSnapshot run, Instant now, String message)
            throws SQLException {
        subAgentRunRepository.writeTerminal(
                connection,
                run.runId(),
                SubAgentRunStatus.FAILED,
                null,
                ErrorCodes.TASK_RESULT_INVALID,
                now);
        backgroundTaskRepository.writeResult(
                connection, run.taskId(), null, BackgroundTaskStatus.FAILED, now);
        TaskDraft deliveryTask = backgroundTaskRepository.findById(run.taskId()).orElse(null);
        recordDeliveryIntent(
                connection,
                deliveryTask,
                BackgroundTaskStatus.FAILED,
                null,
                ErrorCodes.TASK_RESULT_INVALID,
                run.runId());
        connection.commit();
        afterDeliveryCommit(
                deliveryTask,
                BackgroundTaskStatus.FAILED,
                null,
                ErrorCodes.TASK_RESULT_INVALID);
        return new CompletionOutcome.Rejected(message);
    }

    private int parseMaxAttempts(String retryPolicyJson) {
        try {
            JsonNode node = objectMapper.readTree(retryPolicyJson);
            JsonNode max = node.get("maxAttempts");
            if (max != null && max.isInt()) {
                return Math.max(1, max.asInt());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return defaultMaxAttempts;
    }

    private static boolean isRetryable(String code) {
        return ErrorCodes.defaultRetryable(code).orElse(false);
    }

    private void requireDispatchWired() {
        if (dataSource == null
                || backgroundTaskRepository == null
                || subAgentRunRepository == null
                || taskExecutor == null
                || concurrencyGate == null) {
            throw new IllegalStateException("TaskRuntime 未装配 dispatch 依赖（仅 prepare 构造）");
        }
    }

    static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
