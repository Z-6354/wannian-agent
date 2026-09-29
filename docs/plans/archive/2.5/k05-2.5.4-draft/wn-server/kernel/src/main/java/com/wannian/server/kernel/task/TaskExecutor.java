package com.wannian.server.kernel.task;

import com.wannian.server.api.common.SubAgentRunId;

/**
 * 后台 Task 执行器内部 seam（2.5.4 P2 §4.1 / §5.4 · kernel-reference §TaskExecutor）。
 *
 * <p>v2 本机实现为 {@code LocalTaskExecutor}；Remote / wn-agent 更后。本接口不持状态。
 * {@link #dispatch} 本号同步语义（F1）：跑完后由 Runtime 紧随 {@link TaskRuntime#acceptResult}。
 */
public interface TaskExecutor {

    /**
     * 按规格启动一次 SubAgentRun（只读工具批或 NOTIFY 占位）。
     *
     * @param spec 非空；禁止闭包 / Bean 句柄
     * @return {@link DispatchResult.Accepted} 已执行或已接受启动；{@link DispatchResult.Rejected} 非法
     *     type / 超限等（须带可解释 reason；失败码路径见 F12）
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
