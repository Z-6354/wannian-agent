package com.wannian.server.kernel.model;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 单次 {@link ModelPort#decide} 的调用上下文。
 *
 * <p>由 AgentLoop（或探测等调用方）在发起 decide 前组装；适配器只读本快照，
 * 不持有 {@code AgentBudget} 令牌引用。取消与截止的权威仍在调用方。
 *
 * <p>{@link #isCancelledNow()} 供流式读取循环 live 探测：令牌、快照、线程中断任一为真即停。
 *
 * @param turnId 本回合身份字符串；不得为 null
 * @param stepNumber 本轮 Loop 内第几次 decide（从 1 起）
 * @param deadline 本调用不得越过的截止时刻；不得为 null
 * @param cancelled 组装本上下文时是否已请求取消
 * @param traceId 跨层关联 ID；不得为 null；不得含密钥
 * @param cancelProbe 可选 live 取消探测；可为 null
 */
public record ModelCallContext(
        String turnId,
        int stepNumber,
        Instant deadline,
        boolean cancelled,
        String traceId,
        BooleanSupplier cancelProbe) {

    public ModelCallContext {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(traceId, "traceId");
    }

    /** 兼容旧五参构造：无 live probe。 */
    public ModelCallContext(
            String turnId, int stepNumber, Instant deadline, boolean cancelled, String traceId) {
        this(turnId, stepNumber, deadline, cancelled, traceId, null);
    }

    /** 流式循环应调用本方法，而非只读 {@link #cancelled()} 快照。 */
    public boolean isCancelledNow() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (cancelled) {
            return true;
        }
        return cancelProbe != null && cancelProbe.getAsBoolean();
    }
}
