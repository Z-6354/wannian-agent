package com.wannian.server.kernel.tool;

import java.util.Objects;

/**
 * 一次工具调用请求（Agent Loop / 测试入口；不含 Catalog 内部类型）。
 *
 * @param callId 模型侧 tool call id；可空时用空串
 * @param toolName 工具稳定 id
 * @param argumentsJson 原始参数 JSON 文本
 */
public record ToolInvocation(String callId, String toolName, String argumentsJson) {
    public ToolInvocation {
        callId = callId == null ? "" : callId;
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
