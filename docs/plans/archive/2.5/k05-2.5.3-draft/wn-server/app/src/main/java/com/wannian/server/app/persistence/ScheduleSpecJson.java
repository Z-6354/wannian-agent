package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.kernel.task.ScheduleSpec;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link ScheduleSpec} 判别 JSON（type=relative|at|every|cron）。
 *
 * <p>Repo 列 {@code schedule_spec_json} 与 FrozenPlan {@code taskDraft.scheduleSpec} 共用，禁止分叉。
 */
public final class ScheduleSpecJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScheduleSpecJson() {}

    public static String toJson(ScheduleSpec spec) {
        Objects.requireNonNull(spec, "spec");
        try {
            return JSON.writeValueAsString(toObjectNode(spec));
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 ScheduleSpec 失败", ex);
        }
    }

    public static ScheduleSpec fromJson(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return fromNode(JSON.readTree(json));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("非法 schedule_spec_json: " + json, ex);
        }
    }

    /** FrozenPlan 嵌入用：直接写对象节点，避免字符串再 parse。 */
    public static ObjectNode toObjectNode(ScheduleSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ObjectNode node = JSON.createObjectNode();
        switch (spec) {
            case ScheduleSpec.Relative relative -> {
                node.put("type", "relative");
                node.put("offset", relative.offset());
                if (relative.resolvedAt() != null) {
                    node.put("resolvedAt", relative.resolvedAt().toString());
                }
            }
            case ScheduleSpec.At at -> {
                node.put("type", "at");
                node.put("at", at.at().toString());
            }
            case ScheduleSpec.Every every -> {
                node.put("type", "every");
                node.put("everyMs", every.everyMs());
                if (every.anchorAt() != null) {
                    node.put("anchorAt", every.anchorAt().toString());
                }
            }
            case ScheduleSpec.Cron cron -> {
                node.put("type", "cron");
                node.put("expr", cron.expr());
                node.put("tz", cron.tz());
            }
        }
        return node;
    }

    public static ScheduleSpec fromNode(JsonNode node) {
        Objects.requireNonNull(node, "node");
        if (!node.isObject()) {
            throw new IllegalArgumentException("schedule_spec 必须是对象");
        }
        String type = text(node, "type");
        return switch (type) {
            case "relative" -> new ScheduleSpec.Relative(
                    text(node, "offset"), optionalInstant(node, "resolvedAt"));
            case "at" -> new ScheduleSpec.At(Instant.parse(text(node, "at")));
            case "every" -> new ScheduleSpec.Every(
                    node.path("everyMs").asLong(), optionalInstant(node, "anchorAt"));
            case "cron" -> new ScheduleSpec.Cron(text(node, "expr"), text(node, "tz"));
            default -> throw new IllegalArgumentException("未知 schedule type: " + type);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException("schedule_spec 缺少字段: " + field);
        }
        return v.asText();
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return Instant.parse(v.asText());
    }
}
