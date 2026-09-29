package com.wannian.server.kernel.task;

import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/** {@link TaskExecutor#dispatch} 结果（2.5.4 P2 §4.3）。 */
public sealed interface DispatchResult
        permits DispatchResult.Accepted, DispatchResult.Rejected {

    /**
     * 已接受派出。同步执行器（F1）须携带 {@link SubAgentRunResult}，供 Runtime 经接口回报；
     * 异步执行器可传 {@code result=null}，改由独立结果端口回调 {@code acceptResult}。
     */
    record Accepted(SubAgentRunId runId, SubAgentRunResult result) implements DispatchResult {
        public Accepted {
            Objects.requireNonNull(runId, "runId");
        }
    }

    record Rejected(String reason) implements DispatchResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
