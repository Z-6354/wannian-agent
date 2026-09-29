package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import java.util.Objects;

/**
 * {@link TaskRuntime#requestCancel} 结果（P2 §6.2）。
 *
 * <p>本号未启用，实现须返回 {@link NotEnabled} 或抛明确失败，禁止空成功。
 * 真取消语义（CAS / CANCEL_REQUESTED→CANCELLED）在后号。
 */
public sealed interface CancelTaskResult
        permits CancelTaskResult.Accepted,
                CancelTaskResult.AlreadyTerminal,
                CancelTaskResult.NotFound,
                CancelTaskResult.NotEnabled {

    /** 取消已受理（可能仍为 CANCEL_REQUESTED，尚未终态）。 */
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

    /** 本号未启用 requestCancel（2.5.2）。 */
    record NotEnabled(String reason) implements CancelTaskResult {
        public NotEnabled {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
