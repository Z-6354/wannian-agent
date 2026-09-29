package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;

/**
 * 后台 Task 运行时门面（kernel-reference §TaskRuntime）。
 *
 * <p>隐藏状态转换、lease、attempt、retry 与取消顺序。{@link #prepare} 校验提案并产出 Draft（不写库）；
 * {@link #dispatchNext} / {@link #acceptResult} / {@link #requestCancel} / {@link #promoteDueScheduled}
 * 由 app 装配的 {@code DefaultTaskRuntime} 真实现。
 *
 * <p>{@code prepare} 以 {@link TaskProposal#origin()} 为唯一来源上下文（单参，避免双份 origin）。
 */
public interface TaskRuntime {

    /**
     * 校验 Proposal → Resolver → 完整 {@link TaskDraft}（含预分配 id、initialStatus）。
     *
     * @param proposal 非空；须含 origin
     * @return 合法 Draft；非法 → {@link IllegalArgumentException}
     */
    TaskDraft prepare(TaskProposal proposal);

    /**
     * 按预算派出下一可执行 Run（CREATED/READY；不扫未到期 SCHEDULED）。
     *
     * @param budget 非空
     */
    DispatchOutcome dispatchNext(DispatchBudget budget);

    /**
     * 接纳 Executor 回报（CAS + 重试/终态/再调度）。
     *
     * @param result 非空
     */
    CompletionOutcome acceptResult(SubAgentRunResult result);

    /**
     * 请求取消 Task。
     *
     * @param id 非空
     */
    CancelTaskResult requestCancel(BackgroundTaskId id);

    /**
     * 晋升至多 {@code limit} 条到期 {@code SCHEDULED → CREATED}。不跑 Executor。
     *
     * @return 实际晋升条数
     */
    default int promoteDueScheduled(int limit) {
        return 0;
    }
}
