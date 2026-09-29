package com.wannian.server.kernel.task;

import com.wannian.server.api.common.SubAgentRunId;

/**
 * 后台 Task 执行器内部 seam（2.5.4 P2 §4.1 / §5.4 · kernel-reference §TaskExecutor）。
 *
 * <p>v2 本机实现为 {@code LocalTaskExecutor}；Remote / wn-agent 更后。本接口不持状态。
 * {@link #dispatch} 本号同步语义（F1）：{@link DispatchResult.Accepted} 携带
 * {@link SubAgentRunResult}，Runtime 经接口调用 {@link TaskRuntime#acceptResult}（不识别具体实现类）。
 */
public interface TaskExecutor {

    /**
     * 按规格启动一次 SubAgentRun（只读工具批或 NOTIFY）。
     *
     * @param spec 非空；禁止闭包 / Bean 句柄
     * @return {@link DispatchResult.Accepted}（同步路径含 result）或 {@link DispatchResult.Rejected}
     */
    DispatchResult dispatch(SubAgentRunSpec spec);

    /**
     * 请求停止指定 Run（配合 Runtime {@code requestCancel}）。
     *
     * @param runId 非空
     * @param leaseToken 明文 lease（与派出时一致）；持久化侧只存 hash
     * @return Cancelled / NotRunning / Ignored
     */
    CancelDispatchResult cancel(SubAgentRunId runId, String leaseToken);
}
