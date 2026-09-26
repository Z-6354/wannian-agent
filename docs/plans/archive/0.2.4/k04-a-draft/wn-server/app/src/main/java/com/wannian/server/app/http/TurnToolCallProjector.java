package com.wannian.server.app.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.journal.TurnStepStore;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 从 turn_step 投影本回合工具调用，供 /chat/ HTTP 回传。
 *
 * <p>0.2.4-A：默认拒绝原始参数；按工具白名单输出安全字段。历史与后续实时事件须复用
 * {@link #safeArgumentsJson(String, String, String, String)}，禁止第二套规则。
 */
@Component
public final class TurnToolCallProjector {

    private static final int MAX_FIELD_CHARS = 120;

    /** remember_fact 可公开字段（不含 claim / path）。 */
    private static final Set<String> REMEMBER_FACT_FIELDS =
            Set.of("subjectKey", "contentKind", "importance", "scope", "sourceKind");

    private final TurnStepStore turnSteps;
    private final ObjectMapper objectMapper;

    public TurnToolCallProjector(TurnStepStore turnSteps, ObjectMapper objectMapper) {
        this.turnSteps = Objects.requireNonNull(turnSteps, "turnSteps");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 按 step_no 列出 TOOL_CALL；账本关闭或读取失败时返回空列表（不挡回复）。
     */
    public List<ToolCallView> listForTurn(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return List.of();
        }
        List<RunJournalEntry> steps;
        try {
            steps = turnSteps.listByTurnId(turnId.trim());
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<ToolCallView> out = new ArrayList<>();
        for (RunJournalEntry step : steps) {
            if (step.kind() != JournalKind.TOOL_CALL) {
                continue;
            }
            ToolCallView view = project(step);
            if (view != null) {
                out.add(view);
            }
        }
        return List.copyOf(out);
    }

    private ToolCallView project(RunJournalEntry step) {
        String name = "";
        String callId = "";
        String operationId = "";
        String rawArguments = "{}";
        JsonNode request = readTree(step.requestJson());
        if (request != null && request.isObject()) {
            name = text(request, "name");
            callId = text(request, "callId");
            operationId = text(request, "operationId");
            rawArguments = text(request, "argumentsJson");
            if (rawArguments.isEmpty()) {
                rawArguments = "{}";
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        String finished =
                step.finishedAt() == null ? null : step.finishedAt().toString();
        String safeArgs = safeArgumentsJson(name, callId, operationId, rawArguments);
        return new ToolCallView(
                name,
                step.startedAt().toString(),
                finished,
                safeArgs,
                step.status() == null ? "" : step.status(),
                step.errorCode());
    }

    /**
     * 工具参数安全投影（历史 / 实时共用）。
     *
     * <p>始终可含 callId / operationId（若有）；业务参数按白名单，否则 {@code parameters:hidden}。
     */
    public String safeArgumentsJson(
            String toolName, String callId, String operationId, String rawArgumentsJson) {
        Objects.requireNonNull(toolName, "toolName");
        ObjectNode root = objectMapper.createObjectNode();
        putSafeMeta(root, callId, operationId);

        String name = toolName.trim();
        if (isPowerShellFamily(name)) {
            root.put("parameters", "hidden");
            root.put("reason", "powershell_command_not_public");
            return root.toString();
        }
        if (BuiltinToolNames.REMEMBER_FACT.equals(name)) {
            return withWhitelistedArgs(root, rawArgumentsJson, REMEMBER_FACT_FIELDS);
        }
        if (BuiltinToolNames.UPDATE_RELATIONSHIP.equals(name)) {
            // reason / preferredAddress / boundaries 均含用户敏感正文
            root.put("parameters", "hidden");
            root.put("reason", "relationship_fields_not_public");
            return root.toString();
        }
        if (BuiltinToolNames.SEARCH_MEMORY.equals(name)) {
            // query 原文不公开；仅可暴露 limit
            return withWhitelistedArgs(root, rawArgumentsJson, Set.of("limit"));
        }
        if (BuiltinToolNames.HTTP_READ.equals(name)) {
            return withHttpRead(root, rawArgumentsJson);
        }
        if (BuiltinToolNames.CALCULATE.equals(name)) {
            // expression 可能含敏感运算上下文：默认隐藏
            root.put("parameters", "hidden");
            root.put("reason", "expression_not_public");
            return root.toString();
        }
        if (BuiltinToolNames.LIST_TOOLS.equals(name)
                || BuiltinToolNames.CURRENT_TIME.equals(name)) {
            // 无敏感业务参数
            return root.toString();
        }
        root.put("parameters", "hidden");
        root.put("reason", "not_in_public_whitelist");
        return root.toString();
    }

    private String withWhitelistedArgs(ObjectNode root, String rawArgumentsJson, Set<String> allowed) {
        JsonNode args = readTree(rawArgumentsJson);
        if (args == null || !args.isObject()) {
            root.put("parameters", "hidden");
            root.put("reason", "arguments_unparseable");
            return root.toString();
        }
        ObjectNode publicArgs = objectMapper.createObjectNode();
        args.fields()
                .forEachRemaining(
                        entry -> {
                            if (!allowed.contains(entry.getKey())) {
                                return;
                            }
                            String safe = sanitizeField(entry.getValue());
                            if (safe != null) {
                                publicArgs.put(entry.getKey(), safe);
                            }
                        });
        if (publicArgs.isEmpty()) {
            root.put("parameters", "hidden");
            root.put("reason", "no_public_fields");
        } else {
            root.set("args", publicArgs);
        }
        return root.toString();
    }

    private String withHttpRead(ObjectNode root, String rawArgumentsJson) {
        JsonNode args = readTree(rawArgumentsJson);
        if (args == null || !args.isObject()) {
            root.put("parameters", "hidden");
            root.put("reason", "arguments_unparseable");
            return root.toString();
        }
        String url = text(args, "url");
        String hostSummary = summarizeHttpUrl(url);
        if (hostSummary == null) {
            root.put("parameters", "hidden");
            root.put("reason", "url_not_public");
        } else {
            ObjectNode publicArgs = objectMapper.createObjectNode();
            publicArgs.put("host", hostSummary);
            root.set("args", publicArgs);
        }
        return root.toString();
    }

    /**
     * 仅返回 scheme://host[:port]；含 userinfo、query token、无法解析则拒绝。
     */
    static String summarizeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String raw = url.trim();
        String lowered = raw.toLowerCase(Locale.ROOT);
        if (lowered.contains("authorization")
                || lowered.contains("api_key")
                || lowered.contains("apikey")
                || lowered.contains("access_token")
                || lowered.contains("bearer")
                || lowered.contains("sk-")) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return null;
            }
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                return null;
            }
            String query = uri.getQuery();
            if (query != null) {
                String q = query.toLowerCase(Locale.ROOT);
                if (q.contains("token")
                        || q.contains("key=")
                        || q.contains("secret")
                        || q.contains("password")) {
                    return null;
                }
            }
            int port = uri.getPort();
            if (port > 0) {
                return scheme.toLowerCase(Locale.ROOT) + "://" + host + ":" + port;
            }
            return scheme.toLowerCase(Locale.ROOT) + "://" + host;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static void putSafeMeta(ObjectNode root, String callId, String operationId) {
        if (callId != null && !callId.isBlank()) {
            root.put("callId", clip(callId.trim(), 80));
        }
        if (operationId != null && !operationId.isBlank()) {
            root.put("operationId", clip(operationId.trim(), 80));
        }
    }

    private static String sanitizeField(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String text;
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            text = value.asText();
        } else {
            // 嵌套对象默认不公开
            return null;
        }
        if (text.isEmpty()) {
            return null;
        }
        if (containsControlChars(text)) {
            return null;
        }
        String redacted = ErrorLogFields.redact(text);
        if ("[redacted]".equals(redacted) || "[sql-redacted]".equals(redacted)) {
            return null;
        }
        return clip(redacted, MAX_FIELD_CHARS);
    }

    private static boolean containsControlChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return true;
            }
        }
        return false;
    }

    private static boolean isPowerShellFamily(String name) {
        return BuiltinToolNames.POWERSHELL_RESOLVE_5.equals(name)
                || BuiltinToolNames.POWERSHELL_RESOLVE_7.equals(name)
                || BuiltinToolNames.POWERSHELL_RESOLVE_LEGACY.equals(name)
                || name.startsWith("powershell_")
                || name.startsWith("ps_");
    }

    private static String clip(String raw, int max) {
        if (raw.length() <= max) {
            return raw;
        }
        return raw.substring(0, max) + "…";
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }
}
