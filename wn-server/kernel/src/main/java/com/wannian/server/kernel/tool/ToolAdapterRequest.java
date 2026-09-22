package com.wannian.server.kernel.tool;

import java.util.List;
import java.util.Objects;

/**
 * 交给 {@link ToolAdapter} 的一次执行请求。
 *
 * @param visibleTools 本回合模型可见工具；供 {@code list_tools} 等元工具使用；默认空
 */
public record ToolAdapterRequest(
        String operationId, String toolName, String argumentsJson, List<ToolDescriptor> visibleTools) {

    public ToolAdapterRequest(String operationId, String toolName, String argumentsJson) {
        this(operationId, toolName, argumentsJson, List.of());
    }

    public ToolAdapterRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        visibleTools = visibleTools == null ? List.of() : List.copyOf(visibleTools);
    }
}
