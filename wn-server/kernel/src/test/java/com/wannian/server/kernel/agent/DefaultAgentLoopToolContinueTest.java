package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
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
                .isEqualTo(ErrorCodes.BUDGET_DECISIONS_EXHAUSTED);
    }

    @Test
    void systemToolOnlyRoundsDoNotExhaustDecisionBudget() {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    int n = decides.incrementAndGet();
                    if (n <= 5) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c" + n,
                                                "search_memory",
                                                "{\"query\":\"x\"}")),
                                null);
                    }
                    return new ModelOutcome.FinalAnswer("记完了", null);
                };
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentInput input =
                new AgentInput(
                        TurnId.generate(),
                        null,
                        TurnSource.USER,
                        "",
                        null,
                        null,
                        "记住",
                        List.of(
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "search_memory",
                                        "搜索记忆",
                                        "{\"type\":\"object\"}",
                                        false),
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "calculate",
                                        "计算",
                                        "{\"type\":\"object\"}",
                                        true)),
                        null,
                        "你是万年。",
                        null,
                        new InMemoryTurnMemoryPending());
        // max=2：若系统工具也计入，第 3 次 decide 前就会耗尽；不计则 5 次 search + 1 次 final 可通过
        AgentOutcome outcome = loop.run(input, sampleBudget(2));
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(decides.get()).isEqualTo(6);
    }

    @Test
    void rememberFactOnlyRoundsDoNotExhaustDecisionBudget() {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    int n = decides.incrementAndGet();
                    if (n <= 4) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c" + n,
                                                "remember_fact",
                                                "{\"claim\":\"事实" + n + "\","
                                                        + "\"subjectKey\":\"fact." + n + "\","
                                                        + "\"contentKind\":\"USER_FACT\","
                                                        + "\"importance\":\"0.7\"}")),
                                null);
                    }
                    return new ModelOutcome.FinalAnswer("记完了", null);
                };
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentInput input =
                new AgentInput(
                        TurnId.generate(),
                        null,
                        TurnSource.USER,
                        "",
                        null,
                        null,
                        "记住几条",
                        List.of(
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "remember_fact",
                                        "记住事实",
                                        "{\"type\":\"object\"}",
                                        false),
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "calculate",
                                        "计算",
                                        "{\"type\":\"object\"}",
                                        true)),
                        null,
                        "你是万年。",
                        null,
                        new InMemoryTurnMemoryPending());
        AgentOutcome outcome = loop.run(input, sampleBudget(1));
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(decides.get()).isEqualTo(5);
        assertThat(input.pending().snapshotMemories()).hasSize(4);
    }

    @Test
    void systemToolPerNameCapRejectsSixthInvocation() {
        AtomicInteger decides = new AtomicInteger();
        ModelPort model =
                (request, context) -> {
                    int n = decides.incrementAndGet();
                    if (n <= 6) {
                        return new ModelOutcome.ToolCalls(
                                List.of(
                                        new ToolCallRequest(
                                                "c" + n,
                                                "search_memory",
                                                "{\"query\":\"x\"}")),
                                null);
                    }
                    assertThat(request.messages())
                            .anyMatch(
                                    m ->
                                            "tool".equals(m.role())
                                                    && m.content() != null
                                                    && m.content()
                                                            .contains(
                                                                    ErrorCodes
                                                                            .BUDGET_SYSTEM_TOOL_EXHAUSTED));
                    return new ModelOutcome.FinalAnswer("停", null);
                };
        DefaultAgentLoop loop = new DefaultAgentLoop(model, toolRuntime());
        AgentInput input =
                new AgentInput(
                        TurnId.generate(),
                        null,
                        TurnSource.USER,
                        "",
                        null,
                        null,
                        "搜",
                        List.of(
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "search_memory",
                                        "搜索记忆",
                                        "{\"type\":\"object\"}",
                                        false),
                                new com.wannian.server.kernel.tool.ToolDescriptor(
                                        "calculate",
                                        "计算",
                                        "{\"type\":\"object\"}",
                                        true)),
                        null,
                        "你是万年。",
                        null,
                        new InMemoryTurnMemoryPending());
        AgentOutcome outcome = loop.run(input, sampleBudget(2));
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(decides.get()).isEqualTo(7);
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
                null,
                TurnSource.USER,
                "",
                null,
                null,
                "你好",
                List.of(),
                null,
                "你是万年，一个有帮助的助手。",
                null,
                new InMemoryTurnMemoryPending());
    }

    private static AgentBudget sampleBudget(int max) {
        Instant now = Instant.now();
        return new AgentBudget(max, now.plusSeconds(30), now.plusSeconds(60), new AgentBudget.CancelToken());
    }
}
