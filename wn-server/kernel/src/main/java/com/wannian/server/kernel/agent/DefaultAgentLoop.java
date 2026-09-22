package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.tool.ToolExecutionContext;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import com.wannian.server.kernel.tool.ToolInvocation;
import com.wannian.server.kernel.tool.ToolRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AgentLoop} 的默认实现（0.2.2：ToolCalls → ToolRuntime → 回灌 → 再决策）。
 *
 * <p>OWNER: USER — 按 {@code docs/guide/05-agent-loop.md} 演进。
 *
 * <p>不依赖 DecisionPort / WorldAgent / Repository / Spring；不泄漏 Validator/Store/Adapter。
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final String FALLBACK_REFUSAL = "模型拒绝回答";
    private static final String FALLBACK_FAILURE = "模型调用失败";

    private final ModelPort model;
    private final ToolRuntime tools;

    /**
     * @param model 模型决策入口；不得为 null
     */
    public DefaultAgentLoop(ModelPort model) {
        this(model, null);
    }

    /**
     * @param model 模型决策入口；不得为 null
     * @param tools 工具运行时；null 时非空 ToolCalls 仍收口为 {@link ErrorCodes#TOOLS_NOT_ENABLED}
     */
    public DefaultAgentLoop(ModelPort model, ToolRuntime tools) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = tools;
    }

    /**
     * 目的：在预算约束下执行模型—工具—观察循环。
     * 输入保证：上下文已经冻结；budget 为正数。
     * 输出保证：不会返回 null；不会直接提交数据库；不会直接发送 SSE。
     * 禁止：创建具体模型客户端、调用 Controller、执行任意 Shell。
     * 参见：05-agent-loop.md。
     */
    @Override
    public AgentOutcome run(AgentInput input, AgentBudget budget) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(budget, "budget");

        List<ModelMessage> messages = buildInitialMessages(input);
        int completedDecisions = 0;
        List<AgentTrace.Step> steps = new ArrayList<>();

        while (true) {
            Instant now = Instant.now();
            AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, completedDecisions, now);
            if (blocked != null) {
                return withSteps(blocked, steps);
            }

            int stepNumber = completedDecisions + 1;
            ModelCallContext context = buildCallContext(input, budget, stepNumber);
            boolean softPassed = AgentBudgetGate.softDeadlinePassed(budget, Instant.now());

            Instant decideStarted = Instant.now();
            ModelOutcome decision =
                    model.decide(new ModelRequest(messages, input.toolDescriptors()), context);
            completedDecisions++;
            long durationMs = Duration.between(decideStarted, Instant.now()).toMillis();

            switch (decision) {
                case ModelOutcome.FinalAnswer answer -> {
                    if (answer.text().isBlank()) {
                        steps.add(
                            step(
                                    stepNumber,
                                    "FinalAnswer",
                                    durationMs,
                                    answer.usage(),
                                    ErrorCodes.EMPTY_FINAL_ANSWER,
                                    softPassed));
                        return new AgentOutcome.ControlledFailure(
                                ErrorCodes.EMPTY_FINAL_ANSWER,
                                "模型返回了空回答",
                                false,
                                traceFrom(steps));
                    }
                    steps.add(
                            step(
                                    stepNumber,
                                    "FinalAnswer",
                                    durationMs,
                                    answer.usage(),
                                    null,
                                    softPassed));
                    return new AgentOutcome.FinalResponse(
                            answer.text(), answer.usage(), traceFrom(steps));
                }
                case ModelOutcome.ToolCalls toolCalls -> {
                    if (toolCalls.calls().isEmpty()) {
                        steps.add(
                                step(
                                        stepNumber,
                                        "ToolCalls",
                                        durationMs,
                                        toolCalls.usage(),
                                        ErrorCodes.INVALID_MODEL_OUTPUT,
                                        softPassed));
                        return new AgentOutcome.ControlledFailure(
                                ErrorCodes.INVALID_MODEL_OUTPUT,
                                "模型返回了空的工具调用",
                                false,
                                traceFrom(steps));
                    }
                    if (tools == null) {
                        steps.add(
                                step(
                                        stepNumber,
                                        "ToolCalls",
                                        durationMs,
                                        toolCalls.usage(),
                                        ErrorCodes.TOOLS_NOT_ENABLED,
                                        softPassed));
                        return new AgentOutcome.ControlledFailure(
                                ErrorCodes.TOOLS_NOT_ENABLED,
                                "本轮尚未启用工具",
                                false,
                                traceFrom(steps));
                    }
                    steps.add(
                            step(
                                    stepNumber,
                                    "ToolCalls",
                                    durationMs,
                                    toolCalls.usage(),
                                    null,
                                    softPassed));
                    appendToolRound(messages, input, toolCalls, stepNumber);
                    // continue 再决策
                }
                case ModelOutcome.ModelRefusal refusal -> {
                    steps.add(
                            step(
                                    stepNumber,
                                    "ModelRefusal",
                                    durationMs,
                                    refusal.usage(),
                                    ErrorCodes.MODEL_REFUSAL,
                                    softPassed));
                    return new AgentOutcome.ControlledFailure(
                            ErrorCodes.MODEL_REFUSAL,
                            sanitizeUserMessage(refusal.reason(), FALLBACK_REFUSAL),
                            false,
                            traceFrom(steps));
                }
                case ModelOutcome.Failure failure -> {
                    steps.add(
                            step(
                                    stepNumber,
                                    "Failure",
                                    durationMs,
                                    null,
                                    failure.code(),
                                    softPassed));
                    AgentTrace trace = traceFrom(steps);
                    if (ErrorCodes.CANCELLED.equals(failure.code())) {
                        return new AgentOutcome.Cancelled(trace);
                    }
                    return new AgentOutcome.ControlledFailure(
                            failure.code(),
                            sanitizeUserMessage(failure.detail(), FALLBACK_FAILURE),
                            failure.retryable(),
                            trace);
                }
            }
        }
    }

    private void appendToolRound(
            List<ModelMessage> messages,
            AgentInput input,
            ModelOutcome.ToolCalls toolCalls,
            int stepNumber) {
        List<ToolCallRequest> calls = toolCalls.calls();
        messages.add(
                ModelMessage.assistantWithTools(
                        toolCalls.assistantContent(), toolCalls.reasoningContent(), calls));

        String turnKey = input.turnId().toString();
        for (ToolCallRequest call : calls) {
            String operationId = turnKey + ":s" + stepNumber + ":" + call.id();
            ToolExecutionOutcome outcome =
                    tools.execute(
                            new ToolInvocation(call.id(), call.name(), call.argumentsJson()),
                            new ToolExecutionContext(
                                    operationId, turnKey, turnKey, input.toolDescriptors()));
            messages.add(ModelMessage.toolResult(call.id(), formatObservation(call.id(), outcome)));
        }
    }

    private static String formatObservation(String callId, ToolExecutionOutcome outcome) {
        if (outcome instanceof ToolExecutionOutcome.Succeeded succeeded) {
            return "{\"callId\":\""
                    + callId
                    + "\",\"status\":\"succeeded\",\"observation\":"
                    + succeeded.observationJson()
                    + "}";
        }
        if (outcome instanceof ToolExecutionOutcome.Rejected rejected) {
            return "{\"callId\":\""
                    + callId
                    + "\",\"status\":\"rejected\",\"code\":\""
                    + rejected.code()
                    + "\",\"message\":\""
                    + escape(rejected.message())
                    + "\"}";
        }
        if (outcome instanceof ToolExecutionOutcome.Failed failed) {
            return "{\"callId\":\""
                    + callId
                    + "\",\"status\":\"failed\",\"code\":\""
                    + failed.code()
                    + "\",\"message\":\""
                    + escape(failed.message())
                    + "\"}";
        }
        if (outcome instanceof ToolExecutionOutcome.Unknown unknown) {
            return "{\"callId\":\""
                    + callId
                    + "\",\"status\":\"unknown\",\"code\":\""
                    + unknown.code()
                    + "\",\"message\":\""
                    + escape(unknown.message())
                    + "\"}";
        }
        return "{\"callId\":\"" + callId + "\",\"status\":\"failed\",\"code\":\""
                + ErrorCodes.INTERNAL_DEFECT
                + "\"}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static AgentOutcome withSteps(AgentOutcome blocked, List<AgentTrace.Step> prior) {
        if (prior.isEmpty()) {
            return blocked;
        }
        List<String> merged = new ArrayList<>();
        for (AgentTrace.Step s : prior) {
            merged.add(s.toSummary());
        }
        if (blocked instanceof AgentOutcome.ControlledFailure failure) {
            merged.addAll(failure.trace().steps());
            return new AgentOutcome.ControlledFailure(
                    failure.errorCode(),
                    failure.safeUserMessage(),
                    failure.retryable(),
                    new AgentTrace(merged));
        }
        if (blocked instanceof AgentOutcome.Cancelled cancelled) {
            merged.addAll(cancelled.trace().steps());
            return new AgentOutcome.Cancelled(new AgentTrace(merged));
        }
        return blocked;
    }

    private static AgentTrace traceFrom(List<AgentTrace.Step> steps) {
        List<String> summaries = new ArrayList<>(steps.size());
        for (AgentTrace.Step s : steps) {
            summaries.add(s.toSummary());
        }
        return new AgentTrace(summaries);
    }

    private static AgentTrace.Step step(
            int stepNumber,
            String decisionType,
            long durationMs,
            ModelUsage usage,
            String errorCode,
            boolean softDeadlinePassed) {
        return new AgentTrace.Step(
                stepNumber,
                decisionType,
                durationMs,
                usageSummary(usage),
                errorCode,
                softDeadlinePassed);
    }

    private static String usageSummary(ModelUsage usage) {
        if (usage == null) {
            return null;
        }
        return "p=" + usage.promptTokens() + ",c=" + usage.completionTokens();
    }

    /** 写入 Held.detail / ControlledFailure.safeUserMessage 前的消毒；空白则用稳定短文案。 */
    static String sanitizeUserMessage(String raw, String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String redacted = ErrorLogFields.redact(raw.trim());
        if (redacted == null || redacted.isBlank()) {
            return fallback;
        }
        return redacted;
    }

    private static ModelCallContext buildCallContext(
            AgentInput input, AgentBudget budget, int stepNumber) {
        String turnKey = input.turnId().toString();
        return new ModelCallContext(
                turnKey,
                stepNumber,
                budget.hardDeadline(),
                budget.cancelToken().isCancelled(),
                turnKey);
    }

    private static List<ModelMessage> buildInitialMessages(AgentInput input) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", input.systemInstructions()));
        if (input.relationshipSnapshot() != null && !input.relationshipSnapshot().isBlank()) {
            messages.add(new ModelMessage("system", input.relationshipSnapshot()));
        }
        if (input.memoryContext() != null && !input.memoryContext().isBlank()) {
            messages.add(new ModelMessage("system", input.memoryContext()));
        }
        if (!input.conversationExcerpt().isBlank()) {
            messages.add(new ModelMessage("system", input.conversationExcerpt()));
        }
        messages.add(new ModelMessage("user", input.userMessage()));
        return messages;
    }
}
