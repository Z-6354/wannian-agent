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
import com.wannian.server.kernel.tool.ToolDescriptor;
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
 * 本机 {@link TaskExecutor}（2.5.4 + 2.5.12）：只读工具批 + NOTIFY 真开火；F1 同步。
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
    /** 后台只读白名单对本机 Runtime 一律视为本回合可见，避免 TOOLS_NOT_ENABLED。 */
    private final List<ToolDescriptor> whitelistVisible;

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
        this.whitelistVisible =
                READ_ONLY_WHITELIST.stream()
                        .sorted()
                        .map(n -> new ToolDescriptor(n, "background:" + n, "{}"))
                        .toList();
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
                            ? notifyFired(spec)
                            : runToolBatch(spec, cancelled, deadlineAt);
            return new DispatchResult.Accepted(spec.runId(), result);
        } finally {
            cancelFlags.remove(spec.runId().asString());
        }
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

    /** 2.5.12：解析正文 → {@code status=fired}；缺正文失败。 */
    private SubAgentRunResult notifyFired(SubAgentRunSpec spec) {
        try {
            NotifyInput input = NotifyInput.parseRequired(objectMapper, spec.inputJson());
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("kind", "USER_SCHEDULED_NOTIFY");
            envelope.put("status", "fired");
            envelope.put("message", input.message());
            envelope.put("delivery", input.delivery());
            if (input.title() != null) {
                envelope.put("title", input.title());
            }
            return truncateOrFail(spec, objectMapper.writeValueAsString(envelope));
        } catch (IllegalArgumentException ex) {
            return SubAgentRunResult.failure(
                    spec.taskId(),
                    spec.runId(),
                    spec.attemptNo(),
                    spec.leaseToken(),
                    ErrorCodes.TOOL_INVALID_ARGUMENTS,
                    ex.getMessage());
        } catch (Exception ex) {
            return SubAgentRunResult.failure(
                    spec.taskId(),
                    spec.runId(),
                    spec.attemptNo(),
                    spec.leaseToken(),
                    ErrorCodes.TASK_RESULT_INVALID,
                    "NOTIFY 结果序列化失败: " + ex.getMessage());
        }
    }

    private SubAgentRunResult runToolBatch(
            SubAgentRunSpec spec, AtomicBoolean cancelled, Instant deadlineAt) {
        try {
            JsonNode root = objectMapper.readTree(spec.inputJson());
            JsonNode toolsNode = root.get("tools");
            if (toolsNode == null) {
                toolsNode = root.get("calls");
            }
            if (toolsNode == null) {
                toolsNode = root.get("operations");
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
                    if (name == null) {
                        name = text(call, "tool");
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
                    String args = resolveToolArguments(call);
                    String callId = text(call, "id");
                    if (callId == null) {
                        callId = UUID.randomUUID().toString();
                    }
                    ToolExecutionContext ctx =
                            ToolExecutionContext.basic(
                                    "task-" + spec.runId().asString() + "-" + callId,
                                    spec.taskId().asString(),
                                    spec.runId().asString(),
                                    whitelistVisible,
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
            if (out.isEmpty()) {
                return SubAgentRunResult.failure(
                        spec.taskId(),
                        spec.runId(),
                        spec.attemptNo(),
                        spec.leaseToken(),
                        ErrorCodes.TOOL_INVALID_ARGUMENTS,
                        "READ_ONLY_TOOL_BATCH 缺少 tools/calls/operations");
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

    /**
     * 兼容三种写法：arguments / argumentsJson / operations 扁平字段（expression 或 parameters）。
     */
    private String resolveToolArguments(JsonNode call) throws Exception {
        if (call.has("arguments") && !call.get("arguments").isNull()) {
            JsonNode argsNode = call.get("arguments");
            return argsNode.isTextual()
                    ? argsNode.asText()
                    : objectMapper.writeValueAsString(argsNode);
        }
        if (call.has("argumentsJson")) {
            return call.get("argumentsJson").asText("{}");
        }
        if (call.has("parameters") && call.get("parameters").isObject()) {
            return objectMapper.writeValueAsString(call.get("parameters"));
        }
        ObjectNode flat = objectMapper.createObjectNode();
        call.fields()
                .forEachRemaining(
                        e -> {
                            String k = e.getKey();
                            if ("name".equals(k)
                                    || "tool".equals(k)
                                    || "toolName".equals(k)
                                    || "id".equals(k)
                                    || "parameters".equals(k)
                                    || "arguments".equals(k)
                                    || "argumentsJson".equals(k)) {
                                return;
                            }
                            flat.set(k, e.getValue());
                        });
        return objectMapper.writeValueAsString(flat);
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
