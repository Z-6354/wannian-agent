package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.memory.TurnMemoryPending;
import java.util.List;
import java.util.Objects;

/**
 * 交给 {@link ToolAdapter} 的一次执行请求。
 *
 * @param visibleTools 本回合模型可见工具；供 {@code list_tools} 等元工具使用
 * @param pending 本回合记忆/关系 pending；非记忆工具可为 null
 */
public record ToolAdapterRequest(
        String operationId,
        String toolName,
        String argumentsJson,
        List<ToolDescriptor> visibleTools,
        TurnMemoryPending pending) {

    public ToolAdapterRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(visibleTools, "visibleTools");
        visibleTools = List.copyOf(visibleTools);
    }

    /** 兼容内置工具的简洁构造。 */
    public static ToolAdapterRequest basic(
            String operationId,
            String toolName,
            String argumentsJson,
            List<ToolDescriptor> visibleTools,
            TurnMemoryPending pending) {
        return new ToolAdapterRequest(operationId, toolName, argumentsJson, visibleTools, pending);
    }
}
