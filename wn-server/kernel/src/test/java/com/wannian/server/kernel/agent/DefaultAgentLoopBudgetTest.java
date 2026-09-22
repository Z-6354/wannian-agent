package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ToolCallRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DefaultAgentLoop 映射、消毒、空 ToolCalls 与软截止标注（可控 ModelPort；非 live 验收）。 */
class DefaultAgentLoopBudgetTest {

    @Test
    void failureCancelledMapsToCancelledOutcome() {
        ModelPort model =
                (request, context) ->
                        new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.Cancelled.class);
        assertThat(String.join(" ", ((AgentOutcome.Cancelled) outcome).trace().steps()))
                .contains("decision=Failure")
                .contains("code=" + ErrorCodes.CANCELLED);
    }

    @Test
    void softDeadlinePassedAnnotatesTraceOnFirstDecide() {
        Instant now = Instant.now();
        AgentBudget budget =
                new AgentBudget(3, now.minusSeconds(1), now.plusSeconds(60), new AgentBudget.CancelToken());
        ModelPort model = (request, context) -> new ModelOutcome.FinalAnswer("好", null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput(), budget);

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(String.join(" ", ((AgentOutcome.FinalResponse) outcome).trace().steps()))
                .contains("软截止已过")
                .contains("decision=FinalAnswer")
                .contains("usage=p=");
    }

    @Test
    void sanitizesRefusalAndFailureDetailsForUsers() {
        DefaultAgentLoop refusalLoop =
                new DefaultAgentLoop(
                        (request, context) ->
                                new ModelOutcome.ModelRefusal(
                                        "Authorization: Bearer sk-secret-value", null));
        AgentOutcome refusal = refusalLoop.run(sampleInput(), sampleBudget());
        assertThat(refusal).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) refusal).safeUserMessage())
                .isEqualTo("[redacted]")
                .doesNotContain("sk-secret");

        DefaultAgentLoop failureLoop =
                new DefaultAgentLoop(
                        (request, context) ->
                                new ModelOutcome.Failure(
                                        ErrorCodes.DEPENDENCY_UNAVAILABLE,
                                        "INSERT INTO turn VALUES (secret)",
                                        true));
        AgentOutcome failure = failureLoop.run(sampleInput(), sampleBudget());
        assertThat(failure).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) failure).safeUserMessage())
                .isEqualTo("[sql-redacted]")
                .doesNotContain("INSERT INTO");

        assertThat(DefaultAgentLoop.sanitizeUserMessage("   ", "模型调用失败"))
                .isEqualTo("模型调用失败");
    }

    @Test
    void emptyToolCallsYieldInvalidModelOutput() {
        ModelPort model = (request, context) -> new ModelOutcome.ToolCalls(List.of(), null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.ControlledFailure.class);
        AgentOutcome.ControlledFailure failure = (AgentOutcome.ControlledFailure) outcome;
        assertThat(failure.errorCode()).isEqualTo(ErrorCodes.INVALID_MODEL_OUTPUT);
        assertThat(String.join(" ", failure.trace().steps()))
                .contains("code=" + ErrorCodes.INVALID_MODEL_OUTPUT);
    }

    @Test
    void nonEmptyToolCallsStillToolsNotEnabled() {
        ModelPort model =
                (request, context) ->
                        new ModelOutcome.ToolCalls(
                                List.of(new ToolCallRequest("c1", "current_time", "{}")), null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) outcome).errorCode())
                .isEqualTo(ErrorCodes.TOOLS_NOT_ENABLED);
    }

    private static AgentInput sampleInput() {
        return new AgentInput(
                TurnId.generate(),
                TurnSource.USER,
                "",
                null,
                null,
                "你好",
                List.of(),
                null,
                "你是万年，一个有帮助的助手。",
                null);
    }

    private static AgentBudget sampleBudget() {
        return AgentBudget.of(3, 15, 30, Instant.now());
    }
}
