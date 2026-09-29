package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import java.util.Objects;

/**
 * 派出给 {@link TaskExecutor} 的一次 Run 规格（2.5.4 P2 §4.2）。
 *
 * <p>禁止携带闭包、Spring Bean 或不可序列化句柄。
 */
public record SubAgentRunSpec(
        BackgroundTaskId taskId,
        SubAgentRunId runId,
        int attemptNo,
        String leaseToken,
        String executorId,
        TaskType taskType,
        String inputJson,
        ConversationId conversationId,
        CompanionIdentity companionId) {

    public static final String LOCAL_EXECUTOR_ID = "local-primary";

    public SubAgentRunSpec {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 须 ≥ 1");
        }
        Objects.requireNonNull(leaseToken, "leaseToken");
        if (leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken 不得为空");
        }
        Objects.requireNonNull(executorId, "executorId");
        if (executorId.isBlank()) {
            throw new IllegalArgumentException("executorId 不得为空");
        }
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(conversationId, "conversationId");
    }
}
