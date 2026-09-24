package com.wannian.server.kernel.journal;

import com.wannian.server.kernel.error.ErrorLogFields;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import java.util.List;

/**
 * 将模型/工具载荷编成脱敏 JSON 字符串（无 Jackson；kernel 可用）。
 */
public final class JournalJson {

    private JournalJson() {}

    public static String clip(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String redacted = ErrorLogFields.redact(raw);
        if (maxChars > 0 && redacted.length() > maxChars) {
            return redacted.substring(0, maxChars) + "…";
        }
        return redacted;
    }

    public static String messagesJson(List<ModelMessage> messages, boolean includeFull, int maxChars) {
        StringBuilder out = new StringBuilder(256);
        out.append('[');
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            ModelMessage m = messages.get(i);
            out.append('{');
            appendField(out, "role", m.role(), true);
            if (includeFull) {
                appendField(out, "content", clip(nullToEmpty(m.content()), maxChars), false);
                if (m.reasoningContent() != null && !m.reasoningContent().isBlank()) {
                    // 隐藏推理：只记长度，不落全文
                    appendRaw(out, false, "reasoningChars", String.valueOf(m.reasoningContent().length()));
                }
            } else {
                appendRaw(out, false, "contentChars", String.valueOf(nullToEmpty(m.content()).length()));
                appendField(out, "contentDigest", Integer.toHexString(nullToEmpty(m.content()).hashCode()), false);
            }
            if (m.toolCallId() != null) {
                appendField(out, "toolCallId", m.toolCallId(), false);
            }
            if (!m.toolCalls().isEmpty()) {
                out.append(",\"toolCalls\":");
                out.append(toolCallsJson(m.toolCalls(), includeFull, maxChars));
            }
            out.append('}');
        }
        out.append(']');
        return out.toString();
    }

    public static String modelOutcomeJson(ModelOutcome outcome, boolean includeFull, int maxChars) {
        StringBuilder out = new StringBuilder(128);
        out.append('{');
        if (outcome instanceof ModelOutcome.FinalAnswer answer) {
            appendField(out, "type", "FinalAnswer", true);
            appendField(out, "text", includeFull ? clip(answer.text(), maxChars) : null, false);
            if (!includeFull) {
                appendRaw(out, false, "textChars", String.valueOf(answer.text().length()));
            }
            appendUsage(out, answer.usage());
        } else if (outcome instanceof ModelOutcome.ToolCalls tools) {
            appendField(out, "type", "ToolCalls", true);
            if (tools.assistantContent() != null) {
                appendField(
                        out,
                        "assistantContent",
                        includeFull ? clip(tools.assistantContent(), maxChars) : null,
                        false);
            }
            if (tools.reasoningContent() != null && !tools.reasoningContent().isBlank()) {
                appendRaw(out, false, "reasoningChars", String.valueOf(tools.reasoningContent().length()));
            }
            out.append(",\"calls\":");
            out.append(toolCallsJson(tools.calls(), includeFull, maxChars));
            appendUsage(out, tools.usage());
        } else if (outcome instanceof ModelOutcome.ModelRefusal refusal) {
            appendField(out, "type", "ModelRefusal", true);
            appendField(out, "reason", clip(refusal.reason(), maxChars), false);
            appendUsage(out, refusal.usage());
        } else if (outcome instanceof ModelOutcome.Failure failure) {
            appendField(out, "type", "Failure", true);
            appendField(out, "code", failure.code(), false);
            appendField(out, "detail", clip(failure.detail(), maxChars), false);
            appendRaw(out, false, "retryable", String.valueOf(failure.retryable()));
        } else {
            appendField(out, "type", "Unknown", true);
        }
        out.append('}');
        return out.toString();
    }

    public static String toolCallsJson(List<ToolCallRequest> calls, boolean includeFull, int maxChars) {
        StringBuilder out = new StringBuilder(64);
        out.append('[');
        for (int i = 0; i < calls.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            ToolCallRequest c = calls.get(i);
            out.append('{');
            appendField(out, "id", c.id(), true);
            appendField(out, "name", c.name(), false);
            appendField(
                    out,
                    "argumentsJson",
                    includeFull ? clip(c.argumentsJson(), maxChars) : Integer.toHexString(c.argumentsJson().hashCode()),
                    false);
            out.append('}');
        }
        out.append(']');
        return out.toString();
    }

    public static String toolResultJson(ToolExecutionOutcome outcome, int maxChars) {
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        if (outcome instanceof ToolExecutionOutcome.Succeeded s) {
            appendField(out, "status", "succeeded", true);
            appendField(out, "observationJson", clip(s.observationJson(), maxChars), false);
        } else if (outcome instanceof ToolExecutionOutcome.Rejected r) {
            appendField(out, "status", "rejected", true);
            appendField(out, "code", r.code(), false);
            appendField(out, "message", clip(r.message(), maxChars), false);
        } else if (outcome instanceof ToolExecutionOutcome.Failed f) {
            appendField(out, "status", "failed", true);
            appendField(out, "code", f.code(), false);
            appendField(out, "message", clip(f.message(), maxChars), false);
        } else if (outcome instanceof ToolExecutionOutcome.Unknown u) {
            appendField(out, "status", "unknown", true);
            appendField(out, "code", u.code(), false);
            appendField(out, "message", clip(u.message(), maxChars), false);
        } else {
            appendField(out, "status", "failed", true);
        }
        out.append('}');
        return out.toString();
    }

    public static String object(String... alternatingKeyValues) {
        if (alternatingKeyValues.length % 2 != 0) {
            throw new IllegalArgumentException("须成对 key/value");
        }
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        for (int i = 0; i < alternatingKeyValues.length; i += 2) {
            appendField(out, alternatingKeyValues[i], alternatingKeyValues[i + 1], i == 0);
        }
        out.append('}');
        return out.toString();
    }

    private static void appendUsage(StringBuilder out, ModelUsage usage) {
        if (usage == null) {
            return;
        }
        appendRaw(out, false, "promptTokens", String.valueOf(usage.promptTokens()));
        appendRaw(out, false, "completionTokens", String.valueOf(usage.completionTokens()));
    }

    private static void appendField(StringBuilder out, String key, String value, boolean first) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(escape(key)).append('"').append(':');
        if (value == null) {
            out.append("null");
        } else {
            out.append('"').append(escape(value)).append('"');
        }
    }

    private static void appendRaw(StringBuilder out, boolean first, String key, String rawJsonValue) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(escape(key)).append('"').append(':').append(rawJsonValue);
    }

    /** JSON 字符串转义（含控制字符 → {@code \\uXXXX}）；供 JSONL / Loop 载荷共用。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /** 将字符串编成带引号的 JSON 字符串字面量；{@code null} → {@code null}。 */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escape(value) + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
