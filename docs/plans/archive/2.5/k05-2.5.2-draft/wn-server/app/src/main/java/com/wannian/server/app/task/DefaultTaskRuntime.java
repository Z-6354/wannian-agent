package com.wannian.server.app.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.CancelTaskResult;
import com.wannian.server.kernel.task.CompletionOutcome;
import com.wannian.server.kernel.task.DispatchBudget;
import com.wannian.server.kernel.task.DispatchOutcome;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.ResolvedSchedule;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.SubAgentRunResult;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link TaskRuntime} 默认实现（P2 §6）：本号仅 {@link #prepare} 真；其余返回 {@code NotEnabled}。
 *
 * <p>默认时区 {@value #DEFAULT_TIMEZONE}（D5）；默认 retry {@value #DEFAULT_RETRY_POLICY_JSON}；
 * {@code inputJson} 上限 {@link #MAX_INPUT_CHARS}（D2）。不写库。
 */
public final class DefaultTaskRuntime implements TaskRuntime {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    public static final String DEFAULT_RETRY_POLICY_JSON = "{\"maxAttempts\":2}";
    /** D2：256 KiB chars。 */
    public static final int MAX_INPUT_CHARS = 256 * 1024;

    private static final String NOT_ENABLED_REASON = "2.5.2：dispatch/accept/cancel 未启用";

    private final ScheduleResolver scheduleResolver;
    private final Clock clock;

    public DefaultTaskRuntime(ScheduleResolver scheduleResolver, Clock clock) {
        this.scheduleResolver = Objects.requireNonNull(scheduleResolver, "scheduleResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DefaultTaskRuntime(ScheduleResolver scheduleResolver) {
        this(scheduleResolver, Clock.systemUTC());
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
        return new DispatchOutcome.NotEnabled(NOT_ENABLED_REASON);
    }

    @Override
    public CompletionOutcome acceptResult(SubAgentRunResult result) {
        Objects.requireNonNull(result, "result");
        return new CompletionOutcome.NotEnabled(NOT_ENABLED_REASON);
    }

    @Override
    public CancelTaskResult requestCancel(BackgroundTaskId id) {
        Objects.requireNonNull(id, "id");
        return new CancelTaskResult.NotEnabled(NOT_ENABLED_REASON);
    }
}
