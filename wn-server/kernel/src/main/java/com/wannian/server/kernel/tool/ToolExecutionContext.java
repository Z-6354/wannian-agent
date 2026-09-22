package com.wannian.server.kernel.tool;

import java.util.List;
import java.util.Objects;

/**
 * 工具执行上下文：操作身份与来源回合/尝试。
 *
 * @param operationId 稳定操作身份；同 id 用于幂等回放与冲突检测
 * @param sourceTurnId 来源回合
 * @param sourceAttemptId 来源尝试 / executionId
 * @param visibleTools 本回合可见工具描述（元工具用）；默认空
 */
public record ToolExecutionContext(
        String operationId,
        String sourceTurnId,
        String sourceAttemptId,
        List<ToolDescriptor> visibleTools) {
    public ToolExecutionContext(String operationId, String sourceTurnId, String sourceAttemptId) {
        this(operationId, sourceTurnId, sourceAttemptId, List.of());
    }

    public ToolExecutionContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceTurnId, "sourceTurnId");
        Objects.requireNonNull(sourceAttemptId, "sourceAttemptId");
        if (operationId.isBlank() || sourceTurnId.isBlank() || sourceAttemptId.isBlank()) {
            throw new IllegalArgumentException("operationId/sourceTurnId/sourceAttemptId 不得空白");
        }
        visibleTools = visibleTools == null ? List.of() : List.copyOf(visibleTools);
    }
}
