package com.wannian.server.kernel.task;

import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/** {@link TaskExecutor#cancel} 结果（2.5.4 P2 §4.3）。 */
public sealed interface CancelDispatchResult
        permits CancelDispatchResult.Cancelled,
                CancelDispatchResult.NotRunning,
                CancelDispatchResult.Ignored {

    record Cancelled(SubAgentRunId runId) implements CancelDispatchResult {
        public Cancelled {
            Objects.requireNonNull(runId, "runId");
        }
    }

    record NotRunning(SubAgentRunId runId) implements CancelDispatchResult {
        public NotRunning {
            Objects.requireNonNull(runId, "runId");
        }
    }

    record Ignored(String reason) implements CancelDispatchResult {
        public Ignored {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空");
            }
        }
    }
}
