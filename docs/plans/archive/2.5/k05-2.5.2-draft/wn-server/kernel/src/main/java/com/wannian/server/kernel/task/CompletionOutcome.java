package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/**
 * {@link TaskRuntime#acceptResult} 结果（P2 §6.2）。
 *
 * <p>本号未启用，实现须返回 {@link NotEnabled} 或抛明确失败，禁止空成功。
 * 其余变体供 2.5.4+（绑定 task/run/attempt/lease；迟到结果不推进）。
 */
public sealed interface CompletionOutcome
        permits CompletionOutcome.Accepted,
                CompletionOutcome.Ignored,
                CompletionOutcome.Rejected,
                CompletionOutcome.NotEnabled {

    /** 结果已接纳并推进 Task/Run 态。 */
    record Accepted(BackgroundTaskId taskId, SubAgentRunId runId) implements CompletionOutcome {
        public Accepted {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * 合法忽略（如重复回报、已终态）；不重复交付。
     *
     * @param reason 非空
     */
    record Ignored(String reason) implements CompletionOutcome {
        public Ignored {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }

    /**
     * 结果无效（lease/attempt 不匹配等）。
     *
     * @param reason 非空
     */
    record Rejected(String reason) implements CompletionOutcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }

    /** 本号未启用 acceptResult（2.5.2）。 */
    record NotEnabled(String reason) implements CompletionOutcome {
        public NotEnabled {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
