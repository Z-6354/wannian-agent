package com.wannian.server.kernel.agent;

/**
 * 单次有预算的模型—工具—观察循环入口。
 *
 * <p>调用方须在认领成功后传入已组装的不可变 {@link AgentInput}；本接口不打开 Repository、
 * 不分配 sequenceNo、不自行 commit / 发 SSE。
 *
 * <p>实现不得返回 null；持久化与对外完成由 TurnEngine / TurnCommitter 负责。
 *
 * @see AgentInput
 * @see AgentBudget
 * @see AgentOutcome
 */
public interface AgentLoop {

    /**
     * 在预算与取消令牌约束下跑完一次循环，返回封闭结果。
     *
     * @param input  已冻结的上下文快照；不得为 null
     * @param budget 决策次数 / 软硬截止 / 取消令牌；不得为 null
     * @return FinalResponse / BackgroundAccepted / ControlledFailure / Cancelled；不得为 null
     */
    AgentOutcome run(AgentInput input, AgentBudget budget);
}
