package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.time.Instant;
import java.util.Objects;

/** {@code sub_agent_run} 行快照（2.5.4）。 */
public record SubAgentRunSnapshot(
        SubAgentRunId runId,
        BackgroundTaskId taskId,
        int attemptNo,
        SubAgentRunStatus status,
        String executorId,
        String leaseTokenHash,
        Instant leaseExpiresAt,
        String resultJson,
        String errorCode) {

    public SubAgentRunSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 须 ≥ 1");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(executorId, "executorId");
    }
}
