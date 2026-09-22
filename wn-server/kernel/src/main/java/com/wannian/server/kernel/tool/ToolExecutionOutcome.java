package com.wannian.server.kernel.tool;

import java.util.Objects;

/**
 * {@link ToolRuntime#execute} 的封闭结果。
 */
public sealed interface ToolExecutionOutcome {

    String operationId();

    record Succeeded(String operationId, String observationJson) implements ToolExecutionOutcome {
        public Succeeded {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(observationJson, "observationJson");
        }
    }

    record Rejected(String operationId, String code, String message) implements ToolExecutionOutcome {
        public Rejected {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    record Failed(String operationId, String code, String message, boolean retryable)
            implements ToolExecutionOutcome {
        public Failed {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    record Unknown(String operationId, String code, String message) implements ToolExecutionOutcome {
        public Unknown {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
