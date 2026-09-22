package com.wannian.server.kernel.model;

import java.util.List;
import java.util.Objects;

/**
 * 对话消息。role 为 user / assistant / system / tool。
 *
 * <p>工具往返须符合 OpenAI 兼容约定：assistant 带 {@code toolCalls}；tool 带 {@code toolCallId}。
 * 部分供应商（如 DeepSeek thinking）还要求回传 {@code reasoningContent}。
 */
public record ModelMessage(
        String role,
        String content,
        List<ToolCallRequest> toolCalls,
        String toolCallId,
        String reasoningContent) {

    /** 纯文本消息（system / user / 普通 assistant）。 */
    public ModelMessage(String role, String content) {
        this(role, content, List.of(), null, null);
    }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if ("tool".equals(role)) {
            Objects.requireNonNull(content, "content");
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("tool 消息须有 toolCallId");
            }
        } else if (toolCalls.isEmpty()) {
            Objects.requireNonNull(content, "content");
        }
    }

    public static ModelMessage assistantWithTools(
            String content, String reasoningContent, List<ToolCallRequest> calls) {
        Objects.requireNonNull(calls, "calls");
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls 不得为空");
        }
        return new ModelMessage("assistant", content, List.copyOf(calls), null, reasoningContent);
    }

    public static ModelMessage toolResult(String toolCallId, String content) {
        return new ModelMessage("tool", content, List.of(), toolCallId, null);
    }
}
