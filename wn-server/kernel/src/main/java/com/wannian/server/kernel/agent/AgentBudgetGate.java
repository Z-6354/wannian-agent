package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.error.ErrorCodes;
import java.time.Instant;
import java.util.Objects;

/**
 * decide 前的预算 / 取消闸门（供 {@link DefaultAgentLoop} 与单测共用）。
 *
 * <p>软截止：已完成至少一次 decide 后再过 soft → 不再开新 decide；首次 decide 在硬截止前仍允许
 * （「可完成当前步」）。次数 / 软 / 硬分别映射独立错误码。
 */
public final class AgentBudgetGate {

    private AgentBudgetGate() {}

    /**
     * @param budget 本轮预算；不得为 null
     * @param completedDecisions 本轮已计入预算的 decide 次数（首次调用前传 0；系统工具回合可不计入）
     * @param now 判定时刻；不得为 null
     * @return 应立刻返回的 Outcome；允许继续 decide 时返回 null
     */
    public static AgentOutcome beforeDecide(AgentBudget budget, int completedDecisions, Instant now) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(now, "now");
        if (completedDecisions < 0) {
            throw new IllegalArgumentException("completedDecisions 不得为负");
        }
        if (budget.cancelToken().isCancelled()) {
            return new AgentOutcome.Cancelled(AgentTrace.of("取消于decide之前"));
        }
        if (completedDecisions >= budget.maxModelDecisions()) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.BUDGET_DECISIONS_EXHAUSTED,
                    "本轮模型决策次数已用尽",
                    false,
                    AgentTrace.of("决策次数耗尽:completed=" + completedDecisions));
        }
        if (!now.isBefore(budget.hardDeadline())) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.BUDGET_HARD_DEADLINE,
                    "已到硬截止，无法继续调用模型",
                    false,
                    AgentTrace.of("硬截止于decide之前"));
        }
        if (completedDecisions > 0 && !now.isBefore(budget.softDeadline())) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.BUDGET_SOFT_DEADLINE,
                    "已到软截止，不再发起新决策",
                    false,
                    AgentTrace.of("软截止于decide之前:completed=" + completedDecisions));
        }
        return null;
    }

    /** @return 当前是否已过软截止（用于 trace 标注；不影响首次 decide 是否允许） */
    public static boolean softDeadlinePassed(AgentBudget budget, Instant now) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(now, "now");
        return !now.isBefore(budget.softDeadline());
    }
}
