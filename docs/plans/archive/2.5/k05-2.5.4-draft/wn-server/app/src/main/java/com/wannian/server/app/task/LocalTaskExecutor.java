package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.task.CancelDispatchResult;
import com.wannian.server.kernel.task.DispatchResult;
import com.wannian.server.kernel.task.SubAgentRunResult;
import com.wannian.server.kernel.task.SubAgentRunSpec;
import com.wannian.server.kernel.task.TaskExecutor;
import com.wannian.server.kernel.task.TaskType;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.ToolExecutionContext;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import com.wannian.server.kernel.tool.ToolInvocation;
import com.wannian.server.kernel.tool.ToolRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机 {@link TaskExecutor}（2.5.4）：只读工具批 + NOTIFY 占位；F1 同步。
 */
public final class LocalTaskExecutor implements TaskExecutor {

    /** F4 只读白名单。 */
    public static final Set<String> READ_ONLY_WHITELIST =
            Set.of(
                    BuiltinToolNames.CURRENT_TIME,
                    BuiltinToolNames.CALCULATE,
                    BuiltinToolNames.LIST_TOOLS,
                    BuiltinToolNames.SEARCH_MEMORY,
                    BuiltinToolNames.LOAD_SKILL,
                    BuiltinToolNames.HTTP_READ,
                    BuiltinToolNames.POWERSHELL_RESOLVE_5,
                    BuiltinToolNames.POWERSHELL_RESOLVE_7);

    private final ToolRuntime toolRuntime;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration deadline;
    private final int maxResultChars;
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SubAgentRunResult> lastResults =
            new ConcurrentHashMap<>();

    public LocalTaskExecutor(
            ToolRuntime toolRuntime,
            ObjectMapper objectMapper,
            Clock clock,
            Duration deadline,
            int maxResultChars) {
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("deadline 须 > 0");
        }
        if (maxResultChars < 1) {
            throw new IllegalArgumentException("maxResultChars 须 ≥ 1");
        }
        this.maxResultChars = maxResultChars;
    }

    @Override
    public DispatchResult dispatch(SubAgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        TaskType type = spec.taskType();
        if (type != TaskType.READ_ONLY_TOOL_BATCH && type != TaskType.USER_SCHEDULED_NOTIFY) {
            return new DispatchResult.Rejected("不支持的 taskType=" + type);
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(spec.runId().asString(), cancelled);
        try {
            Instant deadlineAt = clock.instant().plus(deadline);
            SubAgentRunResult result =
                    type == TaskType.USER_SCHEDULED_NOTIFY
                            ? notifyPlaceholder(spec)
                            : runToolBatch(spec, cancelled, deadlineAt);
            lastResults.put(spec.runId().asString(), result);
            return new DispatchResult.Accepted(spec.runId());
        } finally {
            cancelFlags.remove(spec.runId().asString());
        }
    }

    /** Runtime 同步模式下取刚跑完的结果。 */
    public SubAgentRunResult takeLastResult(SubAgentRunId runId) {
        Objects.requireNonNull(runId, "runId");
        SubAgentRunResult result = lastResults.remove(runId.asString());
        if (result == null) {
            throw new IllegalStateException("无本地执行结果: " + runId.asString());
        }
        return result;
    }

    @Override
    public CancelDispatchResult cancel(SubAgentRunId runId, String leaseToken) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        AtomicBoolean flag = cancelFlags.get(runId.asString());
        if (flag == null) {
            return new CancelDispatchResult.NotRunning(runId);
        }
        flag.set(true);
        return new CancelDispatchResult.Cancelled(runId);
    }

    private SubAgentRunResult notifyPlaceholder(SubAgentRunSpec spec) {
        String json =
                "{\"kind\":\"USER_SCHEDULED_NOTIFY\",\"status\":\"placeholder\",\"inputChars\":"
                        + spec.inputJson().length()
                        + "}";
        return truncateOrFail(spec, json);
    }

    private SubAgentRunResult runToolBatch(
            SubAgentRunSpec spec, AtomicBoolean cancelled, Instant deadlineAt) {
        try {
            JsonNode root = objectMapper.readTree(spec.inputJson());
            JsonNode toolsNode = root.get("tools");
            if (toolsNode == null) {
                toolsNode = root.get("calls");
            }
            ArrayNode out = objectMapper.createArrayNode();
            if (toolsNode != null && toolsNode.isArray()) {
                for (JsonNode call : toolsNode) {
                    if (cancelled.get()) {
                        return SubAgentRunResult.failure(
                                spec.taskId(),
                                spec.runId(),
                                spec.attemptNo(),
                                spec.leaseToken(),
                                ErrorCodes.CANCELLED,
                                "执行已取消");
                    }
                    if (clock.instant().isAfter(deadlineAt)) {
                        return SubAgentRunResult.failure(
                                spec.taskId(),
                                spec.runId(),
                                spec.attemptNo(),
                                spec.leaseToken(),
                                ErrorCodes.TASK_EXECUTOR_DEADLINE,
                                "Executor deadline 到期");
                    }
                    String name = text(call, "name");
                    if (name == null) {
                        name = text(call, "toolName");
                    }
                    if (name == null || name.isBlank()) {
                        return SubAgentRunResult.failure(
                                spec.taskId(),
                                spec.runId(),
                                spec.attemptNo(),
                                spec.leaseToken(),
                                ErrorCodes.TOOL_INVALID_ARGUMENTS,
                                "工具调用缺少 name");
                    }
                    if (!READ_ONLY_WHITELIST.contains(name)) {
                        return SubAgentRunResult.failure(
                                spec.taskId(),
                                spec.runId(),
                                spec.attemptNo(),
                                spec.leaseToken(),
                                ErrorCodes.TOOL_UNAVAILABLE,
                                "工具不在只读白名单: " + name);
                    }
                    String args = "{}";
                    if (call.has("arguments") && !call.get("arguments").isNull()) {
                        JsonNode argsNode = call.get("arguments");
                        args =
                                argsNode.isTextual()
                                        ? argsNode.asText()
                                        : objectMapper.writeValueAsString(argsNode);
                    } else if (call.has("argumentsJson")) {
                        args = call.get("argumentsJson").asText("{}");
                    }
                    String callId = text(call, "id");
                    if (callId == null) {
                        callId = UUID.randomUUID().toString();
                    }
                    ToolExecutionContext ctx =
                            ToolExecutionContext.basic(
                                    "task-" + spec.runId().asString() + "-" + callId,
                                    spec.taskId().asString(),
                                    spec.runId().asString(),
                                    List.of(),
                                    null);
                    ToolExecutionOutcome outcome =
                            toolRuntime.execute(new ToolInvocation(callId, name, args), ctx);
                    ObjectNode one = objectMapper.createObjectNode();
                    one.put("name", name);
                    one.put("callId", callId);
                    if (outcome instanceof ToolExecutionOutcome.Succeeded s) {
                        one.put("ok", true);
                        one.put("observation", s.observationJson());
                        out.add(one);
                        continue;
                    }
                    String code;
                    String message;
                    if (outcome instanceof ToolExecutionOutcome.Failed f) {
                        code = f.code();
                        message = f.message();
                    } else if (outcome instanceof ToolExecutionOutcome.Rejected r) {
                        code = r.code();
                        message = r.message();
                    } else if (outcome instanceof ToolExecutionOutcome.Unknown u) {
                        code = u.code();
                        message = u.message();
                    } else {
                        code = ErrorCodes.INTERNAL_DEFECT;
                        message = "未知工具结果类型";
                    }
                    String registered =
                            ErrorCodes.isRegistered(code) ? code : ErrorCodes.TOOL_UNAVAILABLE;
                    return SubAgentRunResult.failure(
                            spec.taskId(),
                            spec.runId(),
                            spec.attemptNo(),
                            spec.leaseToken(),
                            registered,
                            message);
                }
            }
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("kind", "READ_ONLY_TOOL_BATCH");
            envelope.set("tools", out);
            return truncateOrFail(spec, objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            return SubAgentRunResult.failure(
                    spec.taskId(),
                    spec.runId(),
                    spec.attemptNo(),
                    spec.leaseToken(),
                    ErrorCodes.TASK_RESULT_INVALID,
                    "工具批解析/执行失败: " + ex.getMessage());
        }
    }

    private SubAgentRunResult truncateOrFail(SubAgentRunSpec spec, String json) {
        if (json.length() > maxResultChars) {
            return SubAgentRunResult.failure(
                    spec.taskId(),
                    spec.runId(),
                    spec.attemptNo(),
                    spec.leaseToken(),
                    ErrorCodes.TASK_RESULT_INVALID,
                    "result_json 超过上限 " + maxResultChars);
        }
        return SubAgentRunResult.success(
                spec.taskId(), spec.runId(), spec.attemptNo(), spec.leaseToken(), json);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
