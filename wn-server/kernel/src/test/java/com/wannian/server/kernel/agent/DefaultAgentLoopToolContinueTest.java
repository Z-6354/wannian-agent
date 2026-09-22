package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.tool.BuiltinToolRegistrar;
import com.wannian.server.kernel.tool.DefaultToolRuntime;
import com.wannian.server.kernel.tool.ToolCatalog;
import com.wannian.server.kernel.tool.ToolRuntime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 0.2.2 阶段 3：工具往返 + 预算打满。 */
class DefaultAgentLoopToolContinueTest {

    @Test
    void toolCallThenFinalAnswer() {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    int n = decides.incrementAndGet();
                    if (n == 1) {
                        return new ModelOutcome.ToolCalls(
                                List.of(new ToolCallRequest("c1", "current_time", "{}")),
                                null,
                                "先查时间",
                                "need tool");
                    }
                    assertThat(request.messages())
                            .anySatisfy(
                                    m -> {
                                        assertThat(m.role()).isEqualTo("assistant");
                                        assertThat(m.toolCalls()).isNotEmpty();
                                        assertThat(m.reasoningContent()).isEqualTo("need tool");
                                    });
                    assertThat(request.messages())
                            .anySatisfy(
                                    m -> {
                                        assertThat(m.role()).isEqualTo("tool");
                                        assertThat(m.toolCallId()).isEqualTo("c1");
                                    });
                    return new ModelOutcome.FinalAnswer("现在时间已读", null);
                };
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget(3));
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(decides.get()).isEqualTo(2);
        assertThat(String.join(" ", ((AgentOutcome.FinalResponse) outcome).trace().steps()))
                .contains("ToolCalls")
                .contains("FinalAnswer");
    }

    @Test
    void alwaysToolCallsExhaustsBudget() {
        ModelPort model =
                (request, context) ->
                        new ModelOutcome.ToolCalls(
                                List.of(new ToolCallRequest("c1", "calculate", "{\"expression\":\"1+1\"}")),
                                null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget(3));
        assertThat(outcome).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) outcome).errorCode())
                .isEqualTo(ErrorCodes.BUDGET_EXHAUSTED);
    }

    @Test
    void unknownToolUsesStableCodeNotToolsNotEnabled() {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    if (decides.incrementAndGet() == 1) {
                        return new ModelOutcome.ToolCalls(
                                List.of(new ToolCallRequest("c1", "no_such_tool", "{}")), null);
                    }
                    return new ModelOutcome.FinalAnswer("收到拒绝", null);
                };
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentOutcome outcome = loop.run(sampleInput(), sampleBudget(3));
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        // 观察消息中应含 TOOL_NOT_FOUND，且未因 TOOLS_NOT_ENABLED 提前失败
        assertThat(decides.get()).isEqualTo(2);
    }

    private static ToolRuntime toolRuntime() {
        ToolCatalog catalog = new ToolCatalog();
        BuiltinToolRegistrar.registerAll(catalog);
        return new DefaultToolRuntime(catalog);
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

    private static AgentBudget sampleBudget(int max) {
        Instant now = Instant.now();
        return new AgentBudget(max, now.plusSeconds(30), now.plusSeconds(60), new AgentBudget.CancelToken());
    }
}
