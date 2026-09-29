package com.wannian.server.app.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.BackgroundTaskView;
import com.wannian.server.kernel.task.ScheduleSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 2.5.9：后台 Task 只读查询（侧栏 / HTTP）。 */
public final class BackgroundTaskQueryService {

    private final BackgroundTaskRepository tasks;
    private final int previewChars;

    public BackgroundTaskQueryService(BackgroundTaskRepository tasks, int previewChars) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
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

    private Map<String, Object> toListItem(BackgroundTaskView row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", row.taskId().asString());
        m.put("status", row.status().name());
        m.put("taskType", row.taskType().name());
        m.put("notifyPolicy", row.notifyPolicy().name());
        m.put("inputPreview", clip(row.inputJson(), 160));
        m.put("scheduled", row.scheduled());
        m.put("scheduleSummary", scheduleSummary(row.scheduleSpec()));
        m.put("nextFireAt", iso(row.nextFireAt()));
        m.put("createdAt", iso(row.createdAt()));
        m.put("cancelable", row.cancelable());
        m.put("resultPreview", clipNullable(row.resultJson(), 120));
        return m;
    }

    private Map<String, Object> toDetail(BackgroundTaskView row) {
        Map<String, Object> m = new LinkedHashMap<>(toListItem(row));
        m.put("conversationId", row.conversationId().asString());
        m.put("timezone", row.timezone());
        m.put("lastFiredAt", iso(row.lastFiredAt()));
        m.put("completedAt", iso(row.completedAt()));
        m.put("resultPreview", clipNullable(row.resultJson(), previewChars));
        m.put("inputPreview", clip(row.inputJson(), previewChars));
        if (row.status() == BackgroundTaskStatus.FAILED && row.resultJson() == null) {
            m.put("errorCode", "FAILED");
        }
        return m;
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
