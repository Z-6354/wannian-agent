package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.memory.TurnMemoryPending;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行上下文：操作身份与来源回合/尝试。
 *
 * @param operationId 稳定操作身份；同 id 用于幂等回放与冲突检测
 * @param sourceTurnId 来源回合
 * @param sourceAttemptId 来源尝试 / executionId
 * @param visibleTools 本回合可见工具描述（元工具用）
 * @param pending 本回合记忆/关系 pending；未接线记忆时可为 null
 */
public record ToolExecutionContext(
        String operationId,
        String sourceTurnId,
        String sourceAttemptId,
        List<ToolDescriptor> visibleTools,
        TurnMemoryPending pending) {

    public ToolExecutionContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceTurnId, "sourceTurnId");
        Objects.requireNonNull(sourceAttemptId, "sourceAttemptId");
        Objects.requireNonNull(visibleTools, "visibleTools");
        if (operationId.isBlank() || sourceTurnId.isBlank() || sourceAttemptId.isBlank()) {
            throw new IllegalArgumentException("operationId/sourceTurnId/sourceAttemptId 不得空白");
        }
        visibleTools = List.copyOf(visibleTools);
    }

    /** 执行上下文的简洁构造。 */
    public static ToolExecutionContext basic(
            String operationId,
            String sourceTurnId,
            String sourceAttemptId,
            List<ToolDescriptor> visibleTools,
            TurnMemoryPending pending) {
        return new ToolExecutionContext(
                operationId,
                sourceTurnId,
                sourceAttemptId,
                visibleTools,
                pending);
    }
}
