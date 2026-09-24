package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.error.ErrorCodes;
import java.util.Objects;

/**
 * ToolRuntime 默认实现：识别 → 校验 → 幂等 → 执行 → 消毒收口。
 *
 * <p>不对 AgentLoop 暴露内部 Validator / Store / Adapter。
 */
public final class DefaultToolRuntime implements ToolRuntime {

    private final ToolCatalog catalog;
    private final InMemoryToolOperationStore operations;

    public DefaultToolRuntime(ToolCatalog catalog) {
        this(catalog, new InMemoryToolOperationStore());
    }

    DefaultToolRuntime(ToolCatalog catalog, InMemoryToolOperationStore operations) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public ToolExecutionOutcome execute(ToolInvocation invocation, ToolExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        String opId = context.operationId();

        var entryOpt = catalog.findByName(invocation.toolName());
        if (entryOpt.isEmpty()) {
            return new ToolExecutionOutcome.Rejected(
                    opId, ErrorCodes.TOOL_NOT_FOUND, "未知工具: " + invocation.toolName());
        }
        ToolCatalog.CatalogEntry entry = entryOpt.get();

        ToolCallValidator.ValidationResult validation =
                ToolCallValidator.validate(
                        entry, invocation.argumentsJson());
        if (!validation.ok()) {
            return new ToolExecutionOutcome.Rejected(
                    opId, validation.code(), validation.message());
        }

        InMemoryToolOperationStore.OperationBinding binding =
                new InMemoryToolOperationStore.OperationBinding(
                        entry.toolName(),
                        InMemoryToolOperationStore.digestArguments(
                                entry.toolName(), invocation.argumentsJson()),
                        context.sourceTurnId(),
                        context.sourceAttemptId());
        InMemoryToolOperationStore.BeginResult begin = operations.begin(opId, binding);
        if (begin instanceof InMemoryToolOperationStore.BeginResult.Replay replay) {
            return replay.outcome();
        }
        if (begin instanceof InMemoryToolOperationStore.BeginResult.Conflict conflict) {
            return new ToolExecutionOutcome.Rejected(opId, conflict.code(), conflict.message());
        }

        ToolAdapterResult adapterResult;
        try {
            adapterResult =
                    entry.executor()
                            .execute(
                                    new ToolAdapterRequest(
                                            opId,
                                            entry.toolName(),
                                            invocation.argumentsJson(),
                                            context.visibleTools(),
                                            context.pending()));
        } catch (RuntimeException ex) {
            ToolExecutionOutcome failed =
                    new ToolExecutionOutcome.Failed(
                            opId,
                            ErrorCodes.INTERNAL_DEFECT,
                            "工具执行异常: " + ex.getMessage(),
                            false);
            operations.complete(opId, failed);
            return failed;
        }

        ToolExecutionOutcome outcome = mapAdapterResult(opId, adapterResult);
        operations.complete(opId, outcome);
        return outcome;
    }

    private static ToolExecutionOutcome mapAdapterResult(String opId, ToolAdapterResult result) {
        if (result instanceof ToolAdapterResult.Succeeded succeeded) {
            return new ToolExecutionOutcome.Succeeded(
                    opId, ToolResultSanitizer.sanitize(succeeded.observationJson()));
        }
        if (result instanceof ToolAdapterResult.Failed failed) {
            return new ToolExecutionOutcome.Failed(
                    opId, failed.code(), failed.message(), failed.retryable());
        }
        if (result instanceof ToolAdapterResult.Unknown unknown) {
            return new ToolExecutionOutcome.Unknown(opId, unknown.code(), unknown.message());
        }
        return new ToolExecutionOutcome.Failed(
                opId, ErrorCodes.INTERNAL_DEFECT, "未知 Adapter 结果类型", false);
    }
}
