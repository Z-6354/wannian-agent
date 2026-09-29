package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import java.time.Instant;
import java.util.Objects;

/**
 * prepare 产物 / 行快照（P2 §4.6）。
 *
 * <p><strong>prepare 成功路径约定：</strong>立即 → {@code scheduleSpec}/{@code nextFireAt} 双
 * null 且 {@code initialStatus=CREATED}；定时 → 双非空且 {@code SCHEDULED}。
 * 由 {@link TaskRuntime#prepare} 实现保证。
 *
 * <p><strong>Repo 读回：</strong>{@code initialStatus} 表示<strong>当前</strong> {@code status}
 *（可为 READY/RUNNING/…）；{@code scheduleSpec}/{@code nextFireAt} 按库列原样加载，允许后号
 * 推进后出现与 prepare 不同的组合。{@code result_json} 不进本类型。
 */
public record TaskDraft(
        BackgroundTaskId taskId,
        ConversationId conversationId,
        TurnId originTurnId,
        CompanionIdentity companionId,
        TaskSource source,
        TaskType taskType,
        NotifyPolicy notifyPolicy,
        String inputJson,
        String retryPolicyJson,
        ScheduleSpec scheduleSpec,
        String timezone,
        Instant nextFireAt,
        BackgroundTaskStatus initialStatus) {

    /**
     * @param companionId 可空
     * @param scheduleSpec 可空
     * @param nextFireAt 可空
     */
    public TaskDraft {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(originTurnId, "originTurnId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(notifyPolicy, "notifyPolicy");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(retryPolicyJson, "retryPolicyJson");
        Objects.requireNonNull(timezone, "timezone");
        if (timezone.isBlank()) {
            throw new IllegalArgumentException("timezone 不得为空");
        }
        Objects.requireNonNull(initialStatus, "initialStatus");
    }
}
