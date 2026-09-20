package com.wannian.server.kernel.model;

import java.time.Instant;
import java.util.Objects;

/** 调用上下文。取消与截止由调用方提供。 */
public record ModelCallContext(
        String turnId, int stepNumber, Instant deadline, boolean cancelled, String traceId) {
    public ModelCallContext {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(traceId, "traceId");
    }
}
