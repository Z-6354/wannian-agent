package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.journal.JournalActor;
import com.wannian.server.kernel.journal.JournalJson;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.prompt.VoiceNudge;
import com.wannian.server.kernel.prompt.CrisisRiskPolicy;
import com.wannian.server.kernel.prompt.CrisisResourceDirectory;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelStreamObserver;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.task.BackgroundPolicy;
import com.wannian.server.kernel.task.BackgroundPolicyContext;
import com.wannian.server.kernel.task.BackgroundPolicyDecision;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolExecutionContext;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import com.wannian.server.kernel.tool.ToolInvocation;
import com.wannian.server.kernel.tool.ToolJson;
import com.wannian.server.kernel.tool.ToolRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@link AgentLoop} 的默认实现（2.2：ToolCalls → ToolRuntime → 回灌 → 再决策）。
 *
 * <p>OWNER: USER — 按 {@code docs/guide/05-agent-loop.md} 演进。
 *
 * <p>不依赖 DecisionPort / WorldAgent / Repository / Spring；不泄漏 Validator/Store/Adapter。
 *
 * <p>2.3.6：可选 {@link RunJournal} 记录 MODEL_CALL / TOOL_CALL（失败不抛回循环）。
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final String FALLBACK_REFUSAL = "模型拒绝回答";
    private static final String FALLBACK_FAILURE = "模型调用失败";
    private static final String CRISIS_FALLBACK =
            "我很担心你现在的安全。如果你或对方正处于立即危险中，请马上联系所在地的紧急服务，并请身边可信任的人陪着你、远离眼前可能造成伤害的物品。你现在是否正处于立即危险中？也可以告诉我你所在的国家或地区，便于判断适用的求助方式。";
    private static final String DISTRESS_FALLBACK =
            "听起来你最近很难熬。愿意的话，可以告诉我发生了什么；如果你开始担心自己会受伤，请马上联系身边可信任的人或所在地的紧急服务。";
    private static final String HARM_TO_OTHERS_FALLBACK =
            "请现在就和那个人保持距离，不要去找他，也不要朝任何人靠近。马上联系所在地的急救服务或警方，并请身边可信赖的人来协助。所在地未知时我不提供猜测号码。";
    private static final String HARM_TO_OTHERS_WITH_HELD_WEAPON_FALLBACK =
            "请立即与那个人保持距离，不要去找对方或靠近任何人。如果武器在你手里，请在远离目标且周围无人靠近的安全位置，把它轻轻放到地面，松手后立即后退；不要携带或再拿起。马上联系所在地的急救服务或警方，并请身边可信赖的人来协助。所在地未知时我不提供猜测号码。";
    private static final String HARM_TO_OTHERS_WITH_NEARBY_WEAPON_FALLBACK =
            "请和那个人及武器保持距离，不要接触、拿起或搬动武器，也不要去找对方。马上联系所在地的急救服务或警方，并请身边可信赖的人来协助。所在地未知时我不提供猜测号码。";
    private static final String MEDICAL_INGESTION_FALLBACK =
            "请立即联系所在地的急救服务或中毒咨询服务，或前往急诊，不要等待症状出现。不要自行催吐；请让身边可信赖的人陪着你或对方，并把药品包装或名称告诉医护人员。所在地未知时我不提供猜测号码。";
    private static final String IMMEDIATE_SELF_HARM_FALLBACK =
            "请马上离开高处、道路或其他危险位置，去有人的安全地方，不要继续实施伤害自己的行为。立即联系所在地的急救服务或警方，并请可信赖的人现在陪着你、协助你获得急诊帮助。所在地未知时我不提供猜测号码。";

    private final ModelPort model;
    private final ToolRuntime tools;
    private final RunJournal journal;
    private final JournalSettings journalSettings;
    private final Supplier<ModelStreamObserver> streamObserver;
    private final AgentActivityListener activityListener;
    private final CrisisResourceDirectory crisisResources;
    /** 2.5.5：可空；null 时 propose_background_task 成功也不返回 BackgroundAccepted。 */
    private final BackgroundPolicy backgroundPolicy;

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
        this(
                model,
                tools,
                journal,
                journalSettings,
                () -> ModelStreamObserver.NOOP,
                AgentActivityListener.NOOP,
                CrisisResourceDirectory.empty(),
                null);
    }

    public DefaultAgentLoop(
            ModelPort model,
            ToolRuntime tools,
            RunJournal journal,
            JournalSettings journalSettings,
            Supplier<ModelStreamObserver> streamObserver,
            AgentActivityListener activityListener) {
        this(
                model,
                tools,
                journal,
                journalSettings,
                streamObserver,
                activityListener,
                CrisisResourceDirectory.empty(),
                null);
    }

    public DefaultAgentLoop(
            ModelPort model,
            ToolRuntime tools,
            RunJournal journal,
            JournalSettings journalSettings,
            Supplier<ModelStreamObserver> streamObserver,
            AgentActivityListener activityListener,
            CrisisResourceDirectory crisisResources) {
        this(
                model,
                tools,
                journal,
                journalSettings,
                streamObserver,
                activityListener,
                crisisResources,
                null);
    }

    public DefaultAgentLoop(
            ModelPort model,
            ToolRuntime tools,
            RunJournal journal,
            JournalSettings journalSettings,
            Supplier<ModelStreamObserver> streamObserver,
            AgentActivityListener activityListener,
            CrisisResourceDirectory crisisResources,
            BackgroundPolicy backgroundPolicy) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = tools;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.journalSettings = Objects.requireNonNull(journalSettings, "journalSettings");
        this.streamObserver = Objects.requireNonNull(streamObserver, "streamObserver");
        this.activityListener = Objects.requireNonNull(activityListener, "activityListener");
        this.crisisResources = Objects.requireNonNull(crisisResources, "crisisResources");
        this.backgroundPolicy = backgroundPolicy;
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

        CrisisRiskPolicy.Decision crisis = CrisisRiskPolicy.classify(input.userMessage());
        if (crisis.requiresSafetyPath()) {
            return deterministicCrisisResponse(input, crisis);
        }
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
            ModelStreamObserver observer = safeObserver();
            ModelOutcome decision;
            decision = model.decide(
                    new ModelRequest(messages, input.toolDescriptors()), context, observer);
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
                    Optional<AgentOutcome> early =
                            appendToolRound(
                                    messages,
                                    input,
                                    toolCalls,
                                    stepNumber,
                                    journalStep,
                                    budget,
                                    systemToolInvocations,
                                    steps);
                    if (early.isPresent()) {
                        return early.get();
                    }
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

    private Optional<AgentOutcome> appendToolRound(
            List<ModelMessage> messages,
            AgentInput input,
            ModelOutcome.ToolCalls toolCalls,
            int stepNumber,
            AtomicInteger journalStep,
            AgentBudget budget,
            Map<String, Integer> systemToolInvocations,
            List<AgentTrace.Step> steps) {
        List<ToolCallRequest> calls = toolCalls.calls();
        messages.add(
                ModelMessage.assistantWithTools(
                        toolCalls.assistantContent(), toolCalls.reasoningContent(), calls));

        String turnKey = input.turnId().asString();
        int systemCap = budget.maxSystemToolInvocationsPerTool();
        AgentOutcome backgroundOutcome = null;
        for (ToolCallRequest call : calls) {
            String operationId = turnKey + ":s" + stepNumber + ":" + call.id();
            Instant toolStarted = Instant.now();
            notifyToolStarted(input, call, operationId);
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
                                            input.pending(),
                                            new com.wannian.server.kernel.tool.ToolInvocationContext(
                                                    input.userMessage(),
                                                    input.conversationId(),
                                                    input.turnId())));
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
                                        input.pending(),
                                        new com.wannian.server.kernel.tool.ToolInvocationContext(
                                                input.userMessage(),
                                                input.conversationId(),
                                                input.turnId())));
            }
            Instant toolFinished = Instant.now();
            notifyToolUpdated(input, call, operationId, outcome);
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

            if (backgroundOutcome == null
                    && BuiltinToolNames.PROPOSE_BACKGROUND_TASK.equals(call.name())
                    && outcome instanceof ToolExecutionOutcome.Succeeded) {
                backgroundOutcome = decideBackgroundAfterPropose(input, call, steps);
            }
        }
        return Optional.ofNullable(backgroundOutcome);
    }

    private AgentOutcome decideBackgroundAfterPropose(
            AgentInput input, ToolCallRequest call, List<AgentTrace.Step> steps) {
        if (backgroundPolicy == null) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.BACKGROUND_NOT_ENABLED,
                    "本轮尚未启用后台策略",
                    false,
                    traceFrom(steps));
        }
        if (input.conversationId() == null) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    "后台提案缺少 conversationId",
                    false,
                    traceFrom(steps));
        }
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(call.argumentsJson());
            TaskType taskType = TaskType.valueOf(ToolJson.requireString(fields, "taskType"));
            String inputJson = ToolJson.requireString(fields, "inputJson");
            String acknowledgementText = ToolJson.requireString(fields, "acknowledgementText");
            NotifyPolicy notify =
                    ToolJson.optionalString(fields, "notifyPolicy")
                            .map(NotifyPolicy::valueOf)
                            .orElse(NotifyPolicy.USER_VISIBLE);
            ScheduleSpec schedule =
                    ToolJson.optionalString(fields, "delay")
                            .map(delay -> (ScheduleSpec) new ScheduleSpec.Relative(delay, null))
                            .orElse(null);
            OriginTurn origin =
                    new OriginTurn(
                            input.turnId(),
                            input.conversationId(),
                            input.pending().companionIdentity());
            TaskProposal proposal =
                    new TaskProposal(
                            TaskSource.USER_LOOP,
                            taskType,
                            notify,
                            inputJson,
                            schedule,
                            origin,
                            null);
            BackgroundPolicyDecision decision =
                    backgroundPolicy.decide(proposal, BackgroundPolicyContext.empty());
            return switch (decision) {
                case BackgroundPolicyDecision.Accept ignored ->
                        new AgentOutcome.BackgroundAccepted(
                                proposal, acknowledgementText, traceFrom(steps));
                case BackgroundPolicyDecision.Reject reject ->
                        new AgentOutcome.ControlledFailure(
                                ErrorCodes.ILLEGAL_ARGUMENT,
                                "无法后台化：" + reject.reason(),
                                false,
                                traceFrom(steps));
            };
        } catch (RuntimeException | ToolJson.ToolJsonException ex) {
            return new AgentOutcome.ControlledFailure(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS,
                    ex.getMessage() == null ? "后台提案参数非法" : ex.getMessage(),
                    false,
                    traceFrom(steps));
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
                turnKey,
                () -> budget.cancelToken().isCancelled());
    }

    private ModelStreamObserver safeObserver() {
        try {
            ModelStreamObserver observer = streamObserver.get();
            return observer == null ? ModelStreamObserver.NOOP : observer;
        } catch (RuntimeException ex) {
            return ModelStreamObserver.NOOP;
        }
    }

    private void notifyToolStarted(AgentInput input, ToolCallRequest call, String operationId) {
        try {
            String rawArgs = call.argumentsJson();
            if (rawArgs == null || rawArgs.isBlank()) {
                rawArgs = "{}";
            }
            // 原始参数交给 ActivityListener；生产侧 StreamingActivityListener 用 TurnToolCallProjector 脱敏
            activityListener.onToolStarted(
                    input.conversationId(),
                    input.turnId(),
                    input.turnId().asString(),
                    call.id(),
                    operationId,
                    call.name(),
                    rawArgs);
        } catch (RuntimeException ignored) {
            // 运行投影不得打断 Loop
        }
    }

    private void notifyToolUpdated(
            AgentInput input,
            ToolCallRequest call,
            String operationId,
            ToolExecutionOutcome outcome) {
        try {
            String status;
            String errorCode = null;
            if (outcome instanceof ToolExecutionOutcome.Succeeded) {
                status = "SUCCEEDED";
            } else if (outcome instanceof ToolExecutionOutcome.Failed failed) {
                status = "FAILED";
                errorCode = failed.code();
            } else if (outcome instanceof ToolExecutionOutcome.Rejected rejected) {
                status = "REJECTED";
                errorCode = rejected.code();
            } else if (outcome instanceof ToolExecutionOutcome.Unknown unknown) {
                status = "UNKNOWN";
                errorCode = unknown.code();
            } else {
                status = "UNKNOWN";
            }
            activityListener.onToolUpdated(
                    input.conversationId(),
                    input.turnId(),
                    input.turnId().asString(),
                    call.id(),
                    operationId,
                    call.name(),
                    status,
                    errorCode,
                    null);
        } catch (RuntimeException ignored) {
            // 运行投影不得打断 Loop
        }
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
        boolean rewrite =
                input.userMessage() != null
                        && (input.userMessage().startsWith("换一种说法")
                                || input.userMessage().contains("近讯已去掉你上一句助手回复"));
        messages.add(
                new ModelMessage(
                        "system", VoiceNudge.forTurn(input.turnId().asString(), rewrite)));
        return messages;
    }

    private AgentOutcome deterministicCrisisResponse(
            AgentInput input, CrisisRiskPolicy.Decision decision) {
        Instant now = Instant.now();
        Optional<CrisisResourceDirectory.VerifiedResource> resource = Optional.empty();
        String matchedRegionAlias = null;
        try {
            Optional<CrisisResourceDirectory.VerifiedResource> candidate =
                    crisisResources.findForExplicitRegion(input.userMessage());
            if (candidate != null) {
                Optional<CrisisResourceDirectory.VerifiedResource> verified = candidate
                        .filter(item -> item.isCurrent(now))
                        .filter(item -> item.explicitlyNamedIn(input.userMessage()).isPresent());
                if (verified.isPresent()) {
                    resource = verified;
                    matchedRegionAlias = verified.get().explicitlyNamedIn(input.userMessage()).orElse(null);
                }
            }
        } catch (RuntimeException ignored) {
            // 资源目录不可用时保持未知地区通用回应，不猜号码。
        }

        String response;
        if (decision.level() == CrisisRiskPolicy.Level.WATCH) {
            response = DISTRESS_FALLBACK;
        } else if (decision.reasons().contains("HARM_TO_OTHERS_EXPLICIT")) {
            if (decision.reasons().contains("WEAPON_HELD_EXPLICIT")) {
                response = HARM_TO_OTHERS_WITH_HELD_WEAPON_FALLBACK;
            } else if (decision.reasons().contains("WEAPON_ACCESS_EXPLICIT")) {
                response = HARM_TO_OTHERS_WITH_NEARBY_WEAPON_FALLBACK;
            } else {
                response = HARM_TO_OTHERS_FALLBACK;
            }
        } else if (decision.reasons().contains("MEDICATION_INGESTION_EXPLICIT")) {
            response = MEDICAL_INGESTION_FALLBACK;
        } else if (decision.reasons().contains("HARMFUL_ACT_EXPLICIT")) {
            response = IMMEDIATE_SELF_HARM_FALLBACK;
        } else {
            response = CRISIS_FALLBACK;
        }
        String resourceFields = "";
        if (decision.level() != CrisisRiskPolicy.Level.WATCH && resource.isPresent()) {
            CrisisResourceDirectory.VerifiedResource verified = resource.get();
            response += " 已核实的当地资源：" + verified.displayName() + "，联系方式："
                    + verified.contact() + "（由 " + verified.verifiedBy() + " 于 "
                    + verified.verifiedAt() + " 核验）。";
            resourceFields = ",\"resourceRegion\":" + JournalJson.quote(verified.regionCode())
                    + ",\"resourceName\":" + JournalJson.quote(verified.displayName())
                    + ",\"resourceVerifiedAt\":" + JournalJson.quote(verified.verifiedAt().toString())
                    + ",\"resourceVerifiedBy\":" + JournalJson.quote(verified.verifiedBy())
                    + ",\"matchedRegionAlias\":" + JournalJson.quote(matchedRegionAlias);
        }

        String fallbackCode = "CRISIS_DETERMINISTIC_RESPONSE";
        String reasonCodes = decision.reasons().stream()
                .map(JournalJson::quote).collect(Collectors.joining(",", "[", "]"));
        String requestJson = "{\"level\":" + JournalJson.quote(decision.level().name())
                + ",\"reasonCodes\":" + reasonCodes + resourceFields + "}";
        String resultJson = JournalJson.object("fallbackCode", fallbackCode,
                "responseMode", "DETERMINISTIC_NO_MODEL_OR_TOOLS");
        try {
            journal.append(RunJournalEntry.of(
                    input.turnId().asString(), conversationKey(input), 0,
                    JournalActor.SYSTEM, JournalKind.CRISIS_DECISION,
                    requestJson, resultJson, "RETURNED", fallbackCode, now, now));
        } catch (RuntimeException ignored) {
            // 审计存储失败不得阻断安全回应。
        }

        List<AgentTrace.Step> audit = List.of(
                step(0, "CrisisRisk-" + decision.level(), 0, null,
                        "CRISIS_SIGNALS_" + String.join("_", decision.reasons()), false),
                step(0, "CrisisFallback", 0, null, fallbackCode, false));
        return new AgentOutcome.FinalResponse(response, null, traceFrom(audit));
    }
}
