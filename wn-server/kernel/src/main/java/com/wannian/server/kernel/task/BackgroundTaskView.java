package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import java.time.Instant;
import java.util.Objects;

/**
 * 2.5.9：后台 Task 只读投影（侧栏 / HTTP）。
 *
 * @param resultJson 可空；展示层再截断
 * @param nextFireAt 可空
 * @param lastFiredAt 可空
 * @param completedAt 可空
 */
public record BackgroundTaskView(
        BackgroundTaskId taskId,
        ConversationId conversationId,
        BackgroundTaskStatus status,
        TaskType taskType,
        NotifyPolicy notifyPolicy,
        String inputJson,
        String resultJson,
        ScheduleSpec scheduleSpec,
        String timezone,
        Instant nextFireAt,
        Instant lastFiredAt,
        Instant createdAt,
        Instant completedAt) {

    public BackgroundTaskView {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(notifyPolicy, "notifyPolicy");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean cancelable() {
        return status != BackgroundTaskStatus.SUCCEEDED
                && status != BackgroundTaskStatus.FAILED
                && status != BackgroundTaskStatus.CANCELLED;
    }

    public boolean scheduled() {
        return scheduleSpec != null;
    }
}
