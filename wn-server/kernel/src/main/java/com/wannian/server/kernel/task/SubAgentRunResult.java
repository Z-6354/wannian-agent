package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.util.Objects;

/**
 * Executor 回报给 {@link TaskRuntime#acceptResult} 的一次 Run 结果（kernel-reference §4）。
 *
 * <p>必须绑定 {@code taskId}/{@code runId}/{@code attemptNo}/{@code leaseToken}；
 * 旧/LOST/已取消尝试的迟到结果不得推进新尝试。本号不调用 acceptResult；类型供签名与后号使用。
 */
public record SubAgentRunResult(
        BackgroundTaskId taskId,
        SubAgentRunId runId,
        int attemptNo,
        String leaseToken,
        boolean succeeded,
        String resultJson,
        String errorCode,
        String errorMessage) {

    /**
     * @param leaseToken 非空明文 token（持久化侧存 hash）
     * @param resultJson 成功时非空 JSON；失败可空
     * @param errorCode 失败时非空；成功须 null
     * @param errorMessage 失败时非空；成功须 null
     */
    public SubAgentRunResult {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 须 ≥ 1");
        }
        Objects.requireNonNull(leaseToken, "leaseToken");
        if (leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken 不得为空");
        }
        if (succeeded) {
            Objects.requireNonNull(resultJson, "resultJson");
            if (errorCode != null || errorMessage != null) {
                throw new IllegalArgumentException("成功结果不得带 errorCode/errorMessage");
            }
        } else {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(errorMessage, "errorMessage");
            if (errorCode.isBlank() || errorMessage.isBlank()) {
                throw new IllegalArgumentException("失败结果 errorCode/errorMessage 不得为空");
            }
        }
    }

    /** 成功回报。 */
    public static SubAgentRunResult success(
            BackgroundTaskId taskId,
            SubAgentRunId runId,
            int attemptNo,
            String leaseToken,
            String resultJson) {
        return new SubAgentRunResult(
                taskId, runId, attemptNo, leaseToken, true, resultJson, null, null);
    }

    /** 失败回报。 */
    public static SubAgentRunResult failure(
            BackgroundTaskId taskId,
            SubAgentRunId runId,
            int attemptNo,
            String leaseToken,
            String errorCode,
            String errorMessage) {
        return new SubAgentRunResult(
                taskId, runId, attemptNo, leaseToken, false, null, errorCode, errorMessage);
    }
}
