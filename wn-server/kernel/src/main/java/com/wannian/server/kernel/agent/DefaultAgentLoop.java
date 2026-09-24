package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolExecutionContext;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import com.wannian.server.kernel.tool.ToolInvocation;
import com.wannian.server.kernel.tool.ToolRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AgentLoop} 的默认实现（0.2.2：ToolCalls → ToolRuntime → 回灌 → 再决策）。
 *
 * <p>OWNER: USER — 按 {@code docs/guide/05-agent-loop.md} 演进。
 *
 * <p>不依赖 DecisionPort / WorldAgent / Repository / Spring；不泄漏 Validator/Store/Adapter。
 *
 * <p>0.2.3-L：可选 {@link RunJournal} 记录 MODEL_CALL / TOOL_CALL（失败不抛回循环）。
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final String FALLBACK_REFUSAL = "模型拒绝回答";
    private static final String FALLBACK_FAILURE = "模型调用失败";

    private final ModelPort model;
    private final ToolRuntime tools;
    private final RunJournal journal;
    private final JournalSettings journalSettings;

    /**
     * @param model 模型决策入口；不得为 null
     */
    public DefaultAgentLoop(ModelPort model) {
        this(model, null, RunJournal.noop(), JournalSettings.DEFAULT);
    }

    /**
     * @param model 模型决策入口；不得为 null
     * @param tools 工具运行时；null 时非空 ToolCalls 仍收口为 {@link ErrorCodes#TOOLS_NOT_ENABLED}
     */
    public DefaultAgentLoop(ModelPort model, ToolRuntime tools) {
        this(model, tools, RunJournal.noop(), JournalSettings.DEFAULT);
    }

    /**
     * @param model 模型决策入口；不得为 null
     * @param tools 工具运行时；可为 null
     * @param journal 行为账本；不得为 null（可用 {@link RunJournal#noop()}）
     * @param journalSettings 账本写入选项；不得为 null
     */
    public DefaultAgentLoop(
            ModelPort model, ToolRuntime tools, RunJournal journal, JournalSettings journalSettings) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = tools;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.journalSettings = Objects.requireNonNull(journalSettings, "journalSettings");
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
        int decideSequence = 0;
        List<AgentTrace.Step> steps = new ArrayList<>();
        AtomicInteger journalStep = new AtomicInteger(0);
        String turnKey = input.turnId().asString();
        Map<String, Integer> systemToolInvocations = new HashMap<>();

        while (true) {
            Instant now = Instant.now();
            AgentOutcome blocked = AgentBudgetGate.beforeDecide(budget, completedDecisions, now);
            if (blocked != null) {
                return withSteps(blocked, steps);
            }

            decideSequence++;
            int stepNumber = decideSequence;
            ModelCallContext context = buildCallContext(input, budget, stepNumber);
            boolean softPassed = AgentBudgetGate.softDeadlinePassed(budget, Instant.now());

            Instant decideStarted = Instant.now();
            List<ModelMessage> requestSnapshot = List.copyOf(messages);
            ModelOutcome decision =
                    model.decide(new ModelRequest(messages, input.toolDescriptors()), context);
            if (countsTowardDecisionBudget(decision, input.toolDescriptors())) {
                completedDecisions++;
            }
            Instant decideFinished = Instant.now();
            long durationMs = Duration.between(decideStarted, decideFinished).toMillis();
            journalModelCall(
                    turnKey,
                    conversationKey(input),
                    input.toolDescriptors(),
                    journalStep,
                    requestSnapshot,
                    decision,
                    decideStarted,
                    decideFinished);

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
                    appendToolRound(
                            messages,
                            input,
                            toolCalls,
                            stepNumber,
                            journalStep,
                            budget,
                            systemToolInvocations);
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
            int stepNumber,
            AtomicInteger journalStep,
            AgentBudget budget,
            Map<String, Integer> systemToolInvocations) {
        List<ToolCallRequest> calls = toolCalls.calls();
        messages.add(
                ModelMessage.assistantWithTools(
                        toolCalls.assistantContent(), toolCalls.reasoningContent(), calls));

        String turnKey = input.turnId().asString();
        int systemCap = budget.maxSystemToolInvocationsPerTool();
        for (ToolCallRequest call : calls) {
            String operationId = turnKey + ":s" + stepNumber + ":" + call.id();
            Instant toolStarted = Instant.now();
            ToolExecutionOutcome outcome;
            boolean systemTool = !toolCountsTowardBudget(call.name(), input.toolDescriptors());
            if (systemTool) {
                int used = systemToolInvocations.getOrDefault(call.name(), 0);
                if (used >= systemCap) {
                    outcome =
                            new ToolExecutionOutcome.Failed(
                                    operationId,
                                    ErrorCodes.BUDGET_SYSTEM_TOOL_EXHAUSTED,
                                    "系统工具 "
                                            + call.name()
                                            + " 本轮已达上限 "
                                            + systemCap
                                            + " 次，请直接回复用户或改用其它工具",
                                    false);
                } else {
                    systemToolInvocations.put(call.name(), used + 1);
                    outcome =
                            tools.execute(
                                    new ToolInvocation(call.id(), call.name(), call.argumentsJson()),
                                    new ToolExecutionContext(
                                            operationId,
                                            turnKey,
                                            turnKey,
                                            input.toolDescriptors(),
                                            input.pending()));
                }
            } else {
                outcome =
                        tools.execute(
                                new ToolInvocation(call.id(), call.name(), call.argumentsJson()),
                                new ToolExecutionContext(
                                        operationId,
                                        turnKey,
                                        turnKey,
                                        input.toolDescriptors(),
                                        input.pending()));
            }
            Instant toolFinished = Instant.now();
            journalToolCall(
                    turnKey,
                    conversationKey(input),
                    journalStep,
                    call,
                    operationId,
                    outcome,
                    toolStarted,
                    toolFinished);
            messages.add(ModelMessage.toolResult(call.id(), formatObservation(call.id(), outcome)));
        }
    }

    private void journalModelCall(
            String turnId,
            String conversationId,
            List<ToolDescriptor> toolDescriptors,
            AtomicInteger journalStep,
            List<ModelMessage> requestMessages,
            ModelOutcome decision,
            Instant started,
            Instant finished) {
        try {
            boolean full = journalSettings.includeFullMessages();
            int max = journalSettings.maxPayloadChars();
            StringBuilder toolNames = new StringBuilder("[");
            if (toolDescriptors != null) {
                for (int i = 0; i < toolDescriptors.size(); i++) {
                    if (i > 0) {
                        toolNames.append(',');
                    }
                    toolNames
                            .append('"')
                            .append(JournalJson.escape(toolDescriptors.get(i).name()))
                            .append('"');
                }
            }
            toolNames.append(']');
            String request =
                    "{\"messages\":"
                            + JournalJson.messagesJson(requestMessages, full, max)
                            + ",\"tools\":"
                            + toolNames
                            + "}";
            String result = JournalJson.modelOutcomeJson(decision, full, max);
            String errorCode = null;
            String status = "SUCCEEDED";
            if (decision instanceof ModelOutcome.Failure failure) {
                errorCode = failure.code();
                status = "FAILED";
            } else if (decision instanceof ModelOutcome.ModelRefusal) {
                errorCode = ErrorCodes.MODEL_REFUSAL;
                status = "FAILED";
            }
            journal.append(
                    RunJournalEntry.of(
                            turnId,
                            conversationId,
                            journalStep.incrementAndGet(),
                            JournalActor.AGENT,
                            JournalKind.MODEL_CALL,
                            request,
                            result,
                            status,
                            errorCode,
                            started,
                            finished));
        } catch (RuntimeException ignored) {
            // 账本不得打断决策
        }
    }

    private void journalToolCall(
            String turnId,
            String conversationId,
            AtomicInteger journalStep,
            ToolCallRequest call,
            String operationId,
            ToolExecutionOutcome outcome,
            Instant started,
            Instant finished) {
        try {
            int max = journalSettings.maxPayloadChars();
            String request =
                    "{\"name\":"
                            + JournalJson.quote(call.name())
                            + ",\"callId\":"
                            + JournalJson.quote(call.id())
                            + ",\"operationId\":"
                            + JournalJson.quote(operationId)
                            + ",\"argumentsJson\":"
                            + JournalJson.quote(JournalJson.clip(call.argumentsJson(), max))
                            + "}";
            String result = JournalJson.toolResultJson(outcome, max);
            String errorCode = null;
            String status = "SUCCEEDED";
            if (outcome instanceof ToolExecutionOutcome.Rejected rejected) {
                errorCode = rejected.code();
                status = "REJECTED";
            } else if (outcome instanceof ToolExecutionOutcome.Failed failed) {
                errorCode = failed.code();
                status = "FAILED";
            } else if (outcome instanceof ToolExecutionOutcome.Unknown unknown) {
                errorCode = unknown.code();
                status = "UNKNOWN";
            }
            journal.append(
                    RunJournalEntry.of(
                            turnId,
                            conversationId,
                            journalStep.incrementAndGet(),
                            JournalActor.AGENT,
                            JournalKind.TOOL_CALL,
                            request,
                            result,
                            status,
                            errorCode,
                            started,
                            finished));
        } catch (RuntimeException ignored) {
            // 账本不得打断工具
        }
    }

    private static String conversationKey(AgentInput input) {
        return input.conversationId() == null ? null : input.conversationId().asString();
    }

    /**
     * 仅含 {@code countsTowardDecisionBudget=false} 的 ToolCalls 不计入决策次数；
     * FinalAnswer / Refusal / Failure / 空调用 / 含普通工具的回合计入。
     *
     * <p>纯系统工具回合不消耗 {@code maxModelDecisions}，但每个系统工具仍受
     * {@link AgentBudget#maxSystemToolInvocationsPerTool()} 约束；超限时该次调用失败回灌，
     * 不执行 Adapter。软/硬墙钟截止仍生效。
     */
    static boolean countsTowardDecisionBudget(ModelOutcome decision, List<ToolDescriptor> visible) {
        if (!(decision instanceof ModelOutcome.ToolCalls toolCalls)) {
            return true;
        }
        if (toolCalls.calls().isEmpty()) {
            return true;
        }
        for (ToolCallRequest call : toolCalls.calls()) {
            if (toolCountsTowardBudget(call.name(), visible)) {
                return true;
            }
        }
        return false;
    }

    private static boolean toolCountsTowardBudget(String toolName, List<ToolDescriptor> visible) {
        if (visible != null) {
            for (ToolDescriptor d : visible) {
                if (d.name().equals(toolName)) {
                    return d.countsTowardDecisionBudget();
                }
            }
        }
        // 未知或不在可见集：保守计入，避免漏计普通工具
        return true;
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
