package com.wannian.server.kernel.model;

import java.util.List;
import java.util.Objects;

/** 模型决策或受控失败。异常不跨过适配器边界。 */
public sealed interface ModelOutcome {

    record FinalAnswer(String text, ModelUsage usage) implements ModelOutcome {
        public FinalAnswer {
            Objects.requireNonNull(text, "text");
            usage = usage == null ? new ModelUsage(0, 0) : usage;
        }
    }

    record ToolCalls(List<ToolCallRequest> calls, ModelUsage usage) implements ModelOutcome {
        public ToolCalls {
            Objects.requireNonNull(calls, "calls");
            calls = List.copyOf(calls);
            usage = usage == null ? new ModelUsage(0, 0) : usage;
        }
    }

    record ModelRefusal(String reason, ModelUsage usage) implements ModelOutcome {
        public ModelRefusal {
            Objects.requireNonNull(reason, "reason");
            usage = usage == null ? new ModelUsage(0, 0) : usage;
        }
    }

    record Failure(String code, String detail, boolean retryable) implements ModelOutcome {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
