package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import java.util.Objects;

/**
 * {@link TaskRuntime#requestCancel} 结果。
 *
 * <p>{@link NotEnabled} 保留兼容旧调用方；现行 {@code DefaultTaskRuntime} 返回 Accepted /
 * AlreadyTerminal / NotFound。
 */
public sealed interface CancelTaskResult
        permits CancelTaskResult.Accepted,
                CancelTaskResult.AlreadyTerminal,
                CancelTaskResult.NotFound,
                CancelTaskResult.NotEnabled {

    /** 取消已受理（终态多为 CANCELLED）。 */
    record Accepted(BackgroundTaskId taskId, BackgroundTaskStatus status) implements CancelTaskResult {
        public Accepted {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(status, "status");
        }
    }

    /** 已是终态，取消无操作。 */
    record AlreadyTerminal(BackgroundTaskId taskId, BackgroundTaskStatus status)
            implements CancelTaskResult {
        public AlreadyTerminal {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(status, "status");
        }
    }

    /** 无此 Task。 */
    record NotFound(BackgroundTaskId taskId) implements CancelTaskResult {
        public NotFound {
            Objects.requireNonNull(taskId, "taskId");
        }
    }

    /** 历史：未启用 cancel 时的占位；现行实现不再返回。 */
    record NotEnabled(String reason) implements CancelTaskResult {
        public NotEnabled {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
