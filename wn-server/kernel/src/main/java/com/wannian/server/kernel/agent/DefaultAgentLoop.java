package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AgentLoop} 的默认实现（0.2.1-B1/B2：有预算的 decide 循环 → FinalResponse）。
 *
 * <p>OWNER: USER — 按 {@code docs/guide/05-agent-loop.md} 演进；工具执行属 0.2.2。
 *
 * <p>0.2.1 仅依赖 {@link ModelPort}；{@code ToolRuntime} 属 0.2.2，本批不构造。
 * 不依赖 DecisionPort / WorldAgent / Repository / Spring。
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final String FALLBACK_REFUSAL = "模型拒绝回答";
    private static final String FALLBACK_FAILURE = "模型调用失败";

    private final ModelPort model;

    /**
     * @param model 模型决策入口；不得为 null
     */
    public DefaultAgentLoop(ModelPort model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * 目的：在预算约束下执行模型—工具—观察循环（B2：ToolCalls 仍失败收口，不执行工具）。
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

        // 雷霆大循环，梦开始的地方

        while (true) {

            // 第一步 轮次判断
            Instant now = Instant.now();
            AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, completedDecisions, now);
            if (blocked != null) {
                return blocked;
            }

            int stepNumber = completedDecisions + 1;
            ModelCallContext context = buildCallContext(input, budget, stepNumber);
            boolean softPassed = AgentBudgetGate.softDeadlinePassed(budget, Instant.now());

            // 第二步 模型决策
            Instant decideStarted = Instant.now();
            ModelOutcome decision = model.decide(new ModelRequest(messages), context);
            completedDecisions++;
            long durationMs = Duration.between(decideStarted, Instant.now()).toMillis();

            // 第三步 四分支均结束循环；ToolCalls 暂不执行、不 continue（0.2.2 再接）
            return switch (decision) {
                case ModelOutcome.FinalAnswer answer -> {
                    if (answer.text().isBlank()) {
                        yield new AgentOutcome.ControlledFailure(
                                ErrorCodes.EMPTY_FINAL_ANSWER,
                                "模型返回了空回答",
                                false,
                                traceOf(
                                        stepNumber,
                                        "FinalAnswer",
                                        durationMs,
                                        answer.usage(),
                                        ErrorCodes.EMPTY_FINAL_ANSWER,
                                        softPassed));
                    }
                    yield new AgentOutcome.FinalResponse(
                            answer.text(),
                            answer.usage(),
                            traceOf(stepNumber, "FinalAnswer", durationMs, answer.usage(), null, softPassed));
                }
                case ModelOutcome.ToolCalls toolCalls -> {
                    if (toolCalls.calls().isEmpty()) {
                        yield new AgentOutcome.ControlledFailure(
                                ErrorCodes.INVALID_MODEL_OUTPUT,
                                "模型返回了空的工具调用",
                                false,
                                traceOf(
                                        stepNumber,
                                        "ToolCalls",
                                        durationMs,
                                        toolCalls.usage(),
                                        ErrorCodes.INVALID_MODEL_OUTPUT,
                                        softPassed));
                    }
                    yield new AgentOutcome.ControlledFailure(
                            ErrorCodes.TOOLS_NOT_ENABLED,
                            "本轮尚未启用工具",
                            false,
                            traceOf(
                                    stepNumber,
                                    "ToolCalls",
                                    durationMs,
                                    toolCalls.usage(),
                                    ErrorCodes.TOOLS_NOT_ENABLED,
                                    softPassed));
                }
                case ModelOutcome.ModelRefusal refusal -> new AgentOutcome.ControlledFailure(
                        ErrorCodes.MODEL_REFUSAL,
                        sanitizeUserMessage(refusal.reason(), FALLBACK_REFUSAL),
                        false,
                        traceOf(
                                stepNumber,
                                "ModelRefusal",
                                durationMs,
                                refusal.usage(),
                                ErrorCodes.MODEL_REFUSAL,
                                softPassed));
                case ModelOutcome.Failure failure -> {
                    AgentTrace trace =
                            traceOf(
                                    stepNumber,
                                    "Failure",
                                    durationMs,
                                    null,
                                    failure.code(),
                                    softPassed);
                    if (ErrorCodes.CANCELLED.equals(failure.code())) {
                        yield new AgentOutcome.Cancelled(trace);
                    }
                    yield new AgentOutcome.ControlledFailure(
                            failure.code(),
                            sanitizeUserMessage(failure.detail(), FALLBACK_FAILURE),
                            failure.retryable(),
                            trace);
                }
            };
        }
    }

    private static AgentTrace traceOf(
            int stepNumber,
            String decisionType,
            long durationMs,
            ModelUsage usage,
            String errorCode,
            boolean softDeadlinePassed) {
        return AgentTrace.ofStep(
                new AgentTrace.Step(
                        stepNumber,
                        decisionType,
                        durationMs,
                        usageSummary(usage),
                        errorCode,
                        softDeadlinePassed));
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

    /**
     * 组装单次 {@link ModelPort#decide} 的调用上下文。
     *
     * @param stepNumber 本轮 Loop 内第几次 decide（从 1 起）
     */
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

    /** B1：首轮上下文。多步 / 工具 observation 在 messages 副本上追加，不改本方法签名。 */
    private static List<ModelMessage> buildInitialMessages(AgentInput input) {
        List<ModelMessage> messages = new ArrayList<>();
        // 安全与人设：Assembler 必注入；本块不得截断
        messages.add(new ModelMessage("system", input.systemInstructions()));
        // 关系快照：称呼/亲密度等；未注入时为 null
        if (input.relationshipSnapshot() != null && !input.relationshipSnapshot().isBlank()) {
            messages.add(new ModelMessage("system", input.relationshipSnapshot()));
        }
        // 长期记忆：未注入时为 null；空串视为无有效记忆
        if (input.memoryContext() != null && !input.memoryContext().isBlank()) {
            messages.add(new ModelMessage("system", input.memoryContext()));
        }
        // 近讯摘录：已裁剪的历史消息，不含当前用户句；空串表示本轮无摘录
        if (!input.conversationExcerpt().isBlank()) {
            messages.add(new ModelMessage("system", input.conversationExcerpt()));
        }
        // 当前用户句：原文完整保留，不得截断
        messages.add(new ModelMessage("user", input.userMessage()));
        return messages;
    }
}
