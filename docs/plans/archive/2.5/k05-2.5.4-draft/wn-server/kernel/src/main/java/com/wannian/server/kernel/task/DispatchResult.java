package com.wannian.server.kernel.task;

import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/** {@link TaskExecutor#dispatch} 结果（2.5.4 P2 §4.3）。 */
public sealed interface DispatchResult
        permits DispatchResult.Accepted, DispatchResult.Rejected {

    record Accepted(SubAgentRunId runId) implements DispatchResult {
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
