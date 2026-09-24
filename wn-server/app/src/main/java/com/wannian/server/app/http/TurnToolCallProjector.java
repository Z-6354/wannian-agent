package com.wannian.server.app.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.journal.TurnStepStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从 turn_step 投影本回合工具调用，供 /chat/ HTTP 回传。 */
@Component
public final class TurnToolCallProjector {

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
        String argumentsJson = "{}";
        JsonNode request = readTree(step.requestJson());
        if (request != null && request.isObject()) {
            name = text(request, "name");
            argumentsJson = text(request, "argumentsJson");
            if (argumentsJson.isEmpty()) {
                argumentsJson = "{}";
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        String finished =
                step.finishedAt() == null ? null : step.finishedAt().toString();
        return new ToolCallView(
                name,
                step.startedAt().toString(),
                finished,
                argumentsJson,
                step.status() == null ? "" : step.status(),
                step.errorCode());
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
