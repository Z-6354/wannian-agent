package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;

/**
 * 后台 Task 运行时门面（P2 §6 / kernel-reference §TaskRuntime）。
 *
 * <p>隐藏状态转换、lease、attempt、retry 与取消顺序。{@link #prepare} 本号真实现（不写库）；
 * {@link #dispatchNext} / {@link #acceptResult} / {@link #requestCancel} 本号明确未启用
 * （返回 {@code NotEnabled} 或抛失败，禁止空成功）。
 *
 * <p>{@code prepare} 以 {@link TaskProposal#origin()} 为唯一来源上下文（origin 已在 proposal 内钉死，
 * 不再另传第二份 {@link OriginTurn}）。相对 kernel-reference 双参 {@code prepare(proposal, origin)}
 * 的差异：本号钉死单参，避免双份 origin 打架。
 */
public interface TaskRuntime {

    /**
     * 校验 Proposal → Resolver → 完整 {@link TaskDraft}（含预分配 id、initialStatus）。
     *
     * @param proposal 非空；须含 origin
     * @return 合法 Draft；非法 → {@link IllegalArgumentException}（D1）
     */
    TaskDraft prepare(TaskProposal proposal);

    /**
     * 按预算派出下一可执行 Run。本号未启用。
     *
     * @param budget 非空
     */
    DispatchOutcome dispatchNext(DispatchBudget budget);

    /**
     * 接纳 Executor 回报。本号未启用。
     *
     * @param result 非空
     */
    CompletionOutcome acceptResult(SubAgentRunResult result);

    /**
     * 请求取消 Task。本号未启用。
     *
     * @param id 非空
     */
    CancelTaskResult requestCancel(BackgroundTaskId id);

    /**
     * 2.5.8：晋升至多 {@code limit} 条到期 {@code SCHEDULED → CREATED}。不跑 Executor。
     *
     * @return 实际晋升条数
     */
    default int promoteDueScheduled(int limit) {
        return 0;
    }
}
