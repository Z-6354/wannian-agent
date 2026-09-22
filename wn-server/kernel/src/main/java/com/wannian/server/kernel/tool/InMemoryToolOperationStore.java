package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.error.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存操作身份库（行为 4）。相同 operationId + 相同绑定可回放；不同绑定冲突。
 */
final class InMemoryToolOperationStore {

    private final ConcurrentMap<String, RecordedOperation> operations = new ConcurrentHashMap<>();

    BeginResult begin(String operationId, OperationBinding binding) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(binding, "binding");
        RecordedOperation created = new RecordedOperation(binding, null);
        RecordedOperation existing = operations.putIfAbsent(operationId, created);
        if (existing == null) {
            return BeginResult.proceed();
        }
        if (!existing.binding().equals(binding)) {
            return BeginResult.conflict(
                    ErrorCodes.TOOL_OPERATION_CONFLICT, "operationId 绑定冲突: " + operationId);
        }
        if (existing.outcome() != null) {
            return BeginResult.replay(existing.outcome());
        }
        return BeginResult.conflict(
                ErrorCodes.TOOL_OPERATION_CONFLICT, "operationId 仍在执行中: " + operationId);
    }

    void complete(String operationId, ToolExecutionOutcome outcome) {
        operations.computeIfPresent(
                operationId,
                (id, current) -> new RecordedOperation(current.binding(), outcome));
    }

    Optional<ToolExecutionOutcome> find(String operationId) {
        RecordedOperation op = operations.get(operationId);
        return op == null ? Optional.empty() : Optional.ofNullable(op.outcome());
    }

    static String digestArguments(String toolName, String argumentsJson) {
        String payload = toolName + '\n' + argumentsJson.trim();
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    record OperationBinding(
            String toolName, String argumentsDigest, String sourceTurnId, String sourceAttemptId) {}

    private record RecordedOperation(OperationBinding binding, ToolExecutionOutcome outcome) {}

    sealed interface BeginResult {
        record Proceed() implements BeginResult {}

        record Replay(ToolExecutionOutcome outcome) implements BeginResult {}

        record Conflict(String code, String message) implements BeginResult {}

        static BeginResult proceed() {
            return new Proceed();
        }

        static BeginResult replay(ToolExecutionOutcome outcome) {
            return new Replay(outcome);
        }

        static BeginResult conflict(String code, String message) {
            return new Conflict(code, message);
        }
    }
}
