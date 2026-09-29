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
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

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
                reclaimExpiredLeases(connection, now);
                connection.commit();
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

                if (taskExecutor instanceof LocalTaskExecutor local) {
                    SubAgentRunResult result = local.takeLastResult(runId);
                    acceptResult(result);
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
                    backgroundTaskRepository.writeResult(
                            connection,
                            run.taskId(),
                            json,
                            BackgroundTaskStatus.SUCCEEDED,
                            now);
                    connection.commit();
                    notifyDelivery(run.taskId(), BackgroundTaskStatus.SUCCEEDED, json, null);
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
                    connection.commit();
                    notifyDelivery(run.taskId(), BackgroundTaskStatus.FAILED, null, code);
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

    private void notifyDelivery(
            BackgroundTaskId taskId,
            BackgroundTaskStatus terminal,
            String resultJson,
            String errorCode) {
        if (taskDeliveryService == null) {
            return;
        }
        try {
            Optional<TaskDraft> task = backgroundTaskRepository.findById(taskId);
            if (task.isEmpty()) {
                return;
            }
            taskDeliveryService.onTaskTerminal(task.get(), terminal, resultJson, errorCode);
        } catch (RuntimeException ignored) {
            // 交付钩不得打断 acceptResult
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

    private void reclaimExpiredLeases(Connection connection, Instant now) {
        int lost = subAgentRunRepository.markLostExpired(connection, now);
        if (lost == 0) {
            return;
        }
        // RUNNING task 若无活跃 Run → READY 或 FAILED
        // 简化：对仍 RUNNING 且最新 Run 为 LOST 的 task 按 retry 复原
        // 逐 task 处理成本高；用 SQL 批量把「RUNNING 且无 LEASED/RUNNING run」置 READY/FAILED
        // 此处用仓库级：扫描 count 不够，实施用 find — 最小实现：依赖下拍 claim 前的 markLost +
        // accept 路径。补充：把 orphan RUNNING 拉回 READY。
        try (var ps =
                connection.prepareStatement(
                        """
                        UPDATE background_task
                        SET status = 'READY', updated_at = ?, revision = revision + 1
                        WHERE status = 'RUNNING'
                          AND id NOT IN (
                            SELECT task_id FROM sub_agent_run
                            WHERE status IN ('LEASED', 'RUNNING')
                          )
                          AND id IN (
                            SELECT task_id FROM sub_agent_run
                            WHERE status = 'LOST'
                          )
                        """)) {
            ps.setString(1, now.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("orphan RUNNING 回收失败", ex);
        }
    }

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
        connection.commit();
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
