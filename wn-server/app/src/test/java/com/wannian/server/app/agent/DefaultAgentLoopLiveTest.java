package com.wannian.server.app.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.app.manage.VendorRecord;
import com.wannian.server.app.model.OpenAiCompatibleModelAdapter;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.agent.AgentInput;
import com.wannian.server.kernel.agent.AgentOutcome;
import com.wannian.server.kernel.agent.DefaultAgentLoop;
import com.wannian.server.kernel.agent.TurnSource;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ToolCallRequest;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 0.2.1-B live 验收：{@link DefaultAgentLoop} 经真实 openai-compatible 适配器出站。
 *
 * <p>禁止用 Fake/Scripted 当作「简单提示 → FinalResponse」的通过证据。无
 * {@code DEEPSEEK_API_KEY} 时跳过 live 用例，避免无密钥 CI 误红。
 *
 * <p>取消路径在 decide 前收口，不需要出站；用「被调用即失败」的桩只断言零次 decide。
 */
class DefaultAgentLoopLiveTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void liveSimplePromptYieldsFinalResponseUsuallyOneDecide() {
        CountingPort model = new CountingPort(liveDeepseek());
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput("只回复一个字：好"), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        AgentOutcome.FinalResponse answer = (AgentOutcome.FinalResponse) outcome;
        assertThat(answer.text()).isNotBlank();
        assertThat(String.join(" ", answer.trace().steps()))
                .doesNotContain("DEEPSEEK_API_KEY")
                .doesNotContain("sk-");
        assertThat(model.decideCount()).isEqualTo(1);
    }

    @Test
    void cancelBeforeDecideReturnsCancelledWithNoOutbound() {
        FailingIfCalledPort model = new FailingIfCalledPort();
        DefaultAgentLoop loop = new DefaultAgentLoop(model);
        AgentBudget budget = sampleBudget();
        budget.cancelToken().cancel();

        AgentOutcome outcome = loop.run(sampleInput("不应出站"), budget);

        assertThat(outcome).isInstanceOf(AgentOutcome.Cancelled.class);
        assertThat(model.decideCount()).isZero();
    }

    @Test
    void emptyToolCallsYieldInvalidModelOutput() {
        ModelPort model = (request, context) -> new ModelOutcome.ToolCalls(List.of(), null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput("空工具"), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.ControlledFailure.class);
        assertThat(((AgentOutcome.ControlledFailure) outcome).errorCode())
                .isEqualTo(ErrorCodes.INVALID_MODEL_OUTPUT);
    }

    @Test
    void toolCallsPathFailsControlledWithoutExecutingTools() {
        ModelPort model =
                (request, context) ->
                        new ModelOutcome.ToolCalls(
                                List.of(new ToolCallRequest("call-1", "current_time", "{}")), null);
        DefaultAgentLoop loop = new DefaultAgentLoop(model);

        AgentOutcome outcome = loop.run(sampleInput("几点了"), sampleBudget());

        assertThat(outcome).isInstanceOf(AgentOutcome.ControlledFailure.class);
        AgentOutcome.ControlledFailure failure = (AgentOutcome.ControlledFailure) outcome;
        assertThat(failure.errorCode()).isEqualTo(ErrorCodes.TOOLS_NOT_ENABLED);
        assertThat(failure.retryable()).isFalse();
    }

    private static ModelPort liveDeepseek() {
        VendorRecord vendor =
                new VendorRecord(
                        "deepseek",
                        "openai-compatible",
                        "https://api.deepseek.com/v1",
                        "DEEPSEEK_API_KEY");
        return new OpenAiCompatibleModelAdapter(
                vendor, "deepseek-flash", HttpClient.newHttpClient(), new ObjectMapper(), System::getenv);
    }

    private static AgentInput sampleInput(String userMessage) {
        return new AgentInput(
                TurnId.generate(),
                null,
                TurnSource.USER,
                "",
                null,
                null,
                userMessage,
                List.of(),
                null,
                "你是万年，一个有帮助的助手。",
                null,
                new InMemoryTurnMemoryPending());
    }

    private static AgentBudget sampleBudget() {
        return AgentBudget.of(3, 15, 30, Instant.now());
    }

    private static final class CountingPort implements ModelPort {
        private final ModelPort inner;
        private final AtomicInteger decides = new AtomicInteger();

        private CountingPort(ModelPort inner) {
            this.inner = inner;
        }

        @Override
        public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
            decides.incrementAndGet();
            return inner.decide(request, context);
        }

        int decideCount() {
            return decides.get();
        }
    }

    private static final class FailingIfCalledPort implements ModelPort {
        private final AtomicInteger decides = new AtomicInteger();

        @Override
        public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
            decides.incrementAndGet();
            throw new AssertionError("取消路径不得发起 decide");
        }

        int decideCount() {
            return decides.get();
        }
    }
}
