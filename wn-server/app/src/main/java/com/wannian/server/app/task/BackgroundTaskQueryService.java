package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.BackgroundTaskView;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.SubAgentRunSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 2.5.9：后台 Task 只读查询（侧栏 / HTTP）。 */
public final class BackgroundTaskQueryService {

    private final BackgroundTaskRepository tasks;
    private final SubAgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final int previewChars;

    public BackgroundTaskQueryService(
            BackgroundTaskRepository tasks,
            SubAgentRunRepository runs,
            ObjectMapper objectMapper,
            int previewChars) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.previewChars = Math.max(64, previewChars);
    }

    public List<Map<String, Object>> list(ConversationId conversationId, boolean activeOnly) {
        List<BackgroundTaskView> rows = tasks.listViewsByConversation(conversationId);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (BackgroundTaskView row : rows) {
            if (activeOnly && !row.cancelable()) {
                continue;
            }
            items.add(toListItem(row));
        }
        return List.copyOf(items);
    }

    public Optional<Map<String, Object>> get(ConversationId conversationId, BackgroundTaskId taskId) {
        Optional<BackgroundTaskView> found = tasks.findView(taskId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BackgroundTaskView row = found.get();
        if (!row.conversationId().equals(conversationId)) {
            return Optional.empty();
        }
        return Optional.of(toDetail(row));
    }

    /**
     * 从列表删除终态任务（含子行）。进行中请先 cancel。
     *
     * @return {@code true} 已删；{@code false} 不存在/非本会话/非终态
     */
    public boolean deleteTerminal(ConversationId conversationId, BackgroundTaskId taskId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(taskId, "taskId");
        return tasks.deleteTerminal(conversationId, taskId);
    }

    private Map<String, Object> toListItem(BackgroundTaskView row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", row.taskId().asString());
        m.put("status", row.status().name());
        m.put("taskType", row.taskType().name());
        m.put("notifyPolicy", row.notifyPolicy().name());
        m.put("inputPreview", humanInputPreview(row.inputJson(), 160));
        m.put("reminderText", reminderText(row.inputJson()));
        m.put("scheduled", row.scheduled());
        m.put("scheduleSummary", scheduleSummary(row.scheduleSpec()));
        m.put("nextFireAt", iso(row.nextFireAt()));
        m.put("createdAt", iso(row.createdAt()));
        m.put("cancelable", row.cancelable());
        m.put(
                "resultPreview",
                clipNullable(
                        humanResultPreview(row.resultJson(), 120), 120));
        putErrorCode(m, row);
        return m;
    }

    private Map<String, Object> toDetail(BackgroundTaskView row) {
        Map<String, Object> m = new LinkedHashMap<>(toListItem(row));
        m.put("conversationId", row.conversationId().asString());
        m.put("timezone", row.timezone());
        m.put("lastFiredAt", iso(row.lastFiredAt()));
        m.put("completedAt", iso(row.completedAt()));
        m.put(
                "resultPreview",
                clipNullable(
                        humanResultPreview(row.resultJson(), previewChars), previewChars));
        m.put("inputPreview", humanInputPreview(row.inputJson(), previewChars));
        m.put("reminderText", reminderText(row.inputJson()));
        putErrorCode(m, row);
        return m;
    }

    /**
     * 人话摘要：NOTIFY title/message/reminder → 工具批 operations → 非 JSON 原文。
     * 不改写库内 input_json；绝不把 operations JSON 原文回给 UI。
     */
    public String humanInputPreview(String inputJson, int max) {
        String remind = reminderText(inputJson);
        if (remind != null && !remind.isBlank()) {
            return clip(remind, max);
        }
        String ops = operationsPreview(inputJson);
        if (ops != null && !ops.isBlank()) {
            return clip(ops, max);
        }
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        String trimmed = inputJson.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "";
        }
        return clip(inputJson, max);
    }

    /** 工具批：tools → calls → operations；兼容 arguments/parameters 嵌套。 */
    String operationsPreview(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputJson);
            JsonNode ops = node;
            if (node != null && node.isObject()) {
                ops = node.get("tools");
                if (ops == null) {
                    ops = node.get("calls");
                }
                if (ops == null) {
                    ops = node.get("operations");
                }
            }
            if (ops == null || !ops.isArray() || ops.isEmpty()) {
                return null;
            }
            int n = ops.size();
            int show = Math.min(3, n);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < show; i++) {
                JsonNode op = ops.get(i);
                if (op == null || !op.isObject()) {
                    continue;
                }
                String tool = firstText(op, "name", "tool", "toolName");
                String label = toolLabel(tool);
                String detail = detailFromToolCall(op);
                if (sb.length() > 0) {
                    sb.append('；');
                }
                sb.append(label);
                if (detail != null) {
                    sb.append(' ').append(detail);
                }
            }
            if (sb.length() == 0) {
                return "工具批处理";
            }
            if (n > show) {
                sb.append(" 等").append(n).append("项");
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String detailFromToolCall(JsonNode op) {
        String direct = firstText(op, "expression", "query", "url", "skill_id");
        if (direct != null) {
            return direct;
        }
        JsonNode args = op.get("arguments");
        if (args == null || args.isNull()) {
            args = op.get("parameters");
        }
        if (args != null && args.isObject()) {
            return firstText(args, "expression", "query", "url", "skill_id");
        }
        if (args != null && args.isTextual()) {
            String raw = args.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                if (parsed != null && parsed.isObject()) {
                    return firstText(parsed, "expression", "query", "url", "skill_id");
                }
            } catch (Exception ignored) {
                return clip(raw, 48);
            }
        }
        return null;
    }

    private static String toolLabel(String tool) {
        if (tool == null || tool.isBlank()) {
            return "工具";
        }
        return switch (tool.trim()) {
            case "calculate" -> "计算";
            case "current_time" -> "查时间";
            case "list_tools" -> "列工具";
            case "search_memory" -> "搜记忆";
            case "load_skill" -> "读技能";
            case "http_read" -> "读网页";
            case "powershell_resolve_5", "powershell_resolve_7" -> "解析路径";
            default -> tool.trim();
        };
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String v = textField(node, field);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 从 input_json 抽出提醒正文；无正文时才退到标题；非对象 JSON 返回 null。 */
    String reminderText(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputJson);
            if (node == null || !node.isObject()) {
                return null;
            }
            String message = textField(node, "message");
            if (message != null) {
                return message;
            }
            String reminder = textField(node, "reminder");
            if (reminder != null) {
                return reminder;
            }
            return textField(node, "title");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 结果预览：message/reminder → 工具批 observation → 禁止整段 JSON。 */
    public String humanResultPreview(String resultJson, int max) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            if (node != null && node.isObject()) {
                String message = textField(node, "message");
                if (message != null) {
                    return clip(message, max);
                }
                String reminder = textField(node, "reminder");
                if (reminder != null) {
                    return clip(reminder, max);
                }
                if ("placeholder".equals(textField(node, "status"))) {
                    return null;
                }
                String batch = toolBatchResultPreview(node);
                if (batch != null && !batch.isBlank()) {
                    return clip(batch, max);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String sanitized = TaskDeliveryService.sanitizePreview(resultJson);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String t = sanitized.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            return null;
        }
        return clipNullable(t, max);
    }

    /** READ_ONLY_TOOL_BATCH envelope →「计算 209934」类短文。 */
    String toolBatchResultPreview(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode tools = root.get("tools");
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int n = tools.size();
        int show = Math.min(3, n);
        for (int i = 0; i < show; i++) {
            JsonNode one = tools.get(i);
            if (one == null || !one.isObject()) {
                continue;
            }
            String name = firstText(one, "name", "tool");
            String label = toolLabel(name);
            String obs = observationSnippet(one.get("observation"));
            if (sb.length() > 0) {
                sb.append('；');
            }
            if (obs != null) {
                sb.append(label).append(' ').append(obs);
            } else if (one.path("ok").asBoolean(false)) {
                sb.append(label).append(" 完成");
            } else {
                sb.append(label).append(" 失败");
            }
        }
        if (sb.length() == 0) {
            return "工具批处理完成";
        }
        if (n > show) {
            sb.append(" 等").append(n).append("项");
        }
        return sb.toString();
    }

    private String observationSnippet(JsonNode observation) {
        if (observation == null || observation.isNull()) {
            return null;
        }
        try {
            JsonNode node = observation.isTextual()
                    ? objectMapper.readTree(observation.asText())
                    : observation;
            if (node == null) {
                return null;
            }
            if (node.isObject()) {
                String v = firstText(node, "result", "value", "text", "answer", "output");
                if (v != null) {
                    return v;
                }
            }
            if (node.isValueNode()) {
                String v = node.asText();
                return v == null || v.isBlank() ? null : v.trim();
            }
            String raw = observation.isTextual() ? observation.asText() : node.toString();
            if (raw == null || raw.isBlank() || raw.trim().startsWith("{")) {
                return null;
            }
            return clip(raw, 64);
        } catch (Exception ignored) {
            String raw = observation.asText();
            if (raw == null || raw.isBlank() || raw.trim().startsWith("{")) {
                return null;
            }
            return clip(raw, 64);
        }
    }

    private static String textField(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asText();
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private void putErrorCode(Map<String, Object> m, BackgroundTaskView row) {
        String code = resolveErrorCode(row);
        if (code != null) {
            m.put("errorCode", code);
        }
    }

    /**
     * 失败码优先取 result_json 内字段；否则取最新 Run 的 error_code。
     * 不再用字面量 {@code FAILED} 冒充真实码。
     */
    private String resolveErrorCode(BackgroundTaskView row) {
        String fromJson = errorCodeFromJson(row.resultJson());
        if (fromJson != null) {
            return fromJson;
        }
        if (row.status() != BackgroundTaskStatus.FAILED
                && row.status() != BackgroundTaskStatus.CANCELLED) {
            return null;
        }
        return runs.findLatestByTaskId(row.taskId())
                .flatMap(runs::findById)
                .map(SubAgentRunSnapshot::errorCode)
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    private String errorCodeFromJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            if (node != null && node.hasNonNull("errorCode")) {
                String code = node.get("errorCode").asText();
                return code == null || code.isBlank() ? null : code.trim();
            }
        } catch (Exception ignored) {
            // 非 JSON 结果：无码
        }
        return null;
    }

    static String scheduleSummary(ScheduleSpec spec) {
        if (spec == null) {
            return null;
        }
        return switch (spec) {
            case ScheduleSpec.Relative relative -> "相对延迟 " + relative.offset();
            case ScheduleSpec.At at -> "单次 " + at.at();
            case ScheduleSpec.Every every -> "每 " + every.everyMs() + "ms";
            case ScheduleSpec.Cron cron -> "cron " + cron.expr() + " @" + cron.tz();
        };
    }

    private static String iso(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String clipNullable(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return clip(text, max);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
