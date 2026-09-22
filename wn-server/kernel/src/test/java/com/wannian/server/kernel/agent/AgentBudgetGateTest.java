package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 预算闸门单测：次数 / 软硬截止 / 取消（不依赖 ModelPort）。 */
class AgentBudgetGateTest {

    private static final Instant T0 = Instant.parse("2026-09-22T04:00:00Z");

    @Test
    void maxModelDecisionsBlocksWhenCompletedReachesCap() {
        AgentBudget budget = new AgentBudget(3, T0.plusSeconds(15), T0.plusSeconds(30), new AgentBudget.CancelToken());

        AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, 3, T0);

        assertThat(blocked).isInstanceOf(AgentOutcome.ControlledFailure.class);
        AgentOutcome.ControlledFailure failure = (AgentOutcome.ControlledFailure) blocked;
        assertThat(failure.errorCode()).isEqualTo(ErrorCodes.BUDGET_EXHAUSTED);
        assertThat(failure.safeUserMessage()).contains("决策次数");
    }

    @Test
    void softDeadlineAllowsFirstDecideButBlocksSubsequent() {
        AgentBudget budget =
                new AgentBudget(3, T0.minusSeconds(1), T0.plusSeconds(30), new AgentBudget.CancelToken());

        assertThat(AgentBudgetGate.beforeDecide(budget, 0, T0)).isNull();
        assertThat(AgentBudgetGate.softDeadlinePassed(budget, T0)).isTrue();

        AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, 1, T0);
        assertThat(blocked).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) blocked).errorCode())
                .isEqualTo(ErrorCodes.BUDGET_EXHAUSTED);
        assertThat(((AgentOutcome.ControlledFailure) blocked).safeUserMessage()).contains("软截止");
    }

    @Test
    void hardDeadlineBlocksEvenFirstDecide() {
        AgentBudget budget =
                new AgentBudget(3, T0.minusSeconds(10), T0.minusSeconds(1), new AgentBudget.CancelToken());

        AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, 0, T0);
        assertThat(blocked).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) blocked).safeUserMessage()).contains("硬截止");
    }

    @Test
    void cancelBeforeDecideReturnsCancelled() {
        AgentBudget.CancelToken token = new AgentBudget.CancelToken();
        token.cancel();
        AgentBudget budget = new AgentBudget(3, T0.plusSeconds(15), T0.plusSeconds(30), token);

        AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, 0, T0);
        assertThat(blocked).isInstanceOf(AgentOutcome.Cancelled.class);
    }
}
