package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/**
 * {@link TaskRuntime#dispatchNext} 结果（P2 §6.2）。
 *
 * <p>本号 {@code dispatchNext} 未启用，实现须返回 {@link NotEnabled} 或抛明确失败，禁止空成功。
 * {@link Dispatched} / {@link NoneReady} / {@link Deferred} 供 2.5.4 真语义。
 */
public sealed interface DispatchOutcome
        permits DispatchOutcome.Dispatched,
                DispatchOutcome.NoneReady,
                DispatchOutcome.Deferred,
                DispatchOutcome.NotEnabled {

    /** 已派出一个 Background Run。 */
    record Dispatched(BackgroundTaskId taskId, SubAgentRunId runId) implements DispatchOutcome {
        public Dispatched {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    /** 无可执行 READY 行（或预算内无可派）。 */
    record NoneReady() implements DispatchOutcome {}

    /**
     * 有候选但本拍不派（如抢模型 defer、门闩占满）。
     *
     * @param reason 非空说明
     */
    record Deferred(String reason) implements DispatchOutcome {
        public Deferred {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }

    /**
     * 本号未启用 dispatch（2.5.2）。
     *
     * @param reason 非空
     */
    record NotEnabled(String reason) implements DispatchOutcome {
        public NotEnabled {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
