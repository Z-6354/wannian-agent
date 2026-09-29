package com.wannian.server.kernel.task;

import java.util.Objects;

/**
 * {@link BackgroundPolicy#decide} 的封闭结果（2.5.5）。
 *
 * <p>{@link Accept}：允许 Loop 返回 {@code AgentOutcome.BackgroundAccepted}。
 * {@link Reject}：禁止后台化；Loop 须 Final 或 ControlledFailure，不得 BackgroundAccepted。
 */
public sealed interface BackgroundPolicyDecision {

    /**
     * 允许后台化。
     *
     * @param notes 可空；仅诊断/日志，不得含密钥
     */
    record Accept(String notes) implements BackgroundPolicyDecision {
        public Accept {
            if (notes != null) {
                notes = notes.trim();
                if (notes.isEmpty()) {
                    notes = null;
                }
            }
        }

        /** 无备注的 Accept。 */
        public static Accept of() {
            return new Accept(null);
        }
    }

    /**
     * 禁止后台化。
     *
     * @param reason 非空短因；供 Loop 收口文案 / 日志
     */
    record Reject(String reason) implements BackgroundPolicyDecision {
        public Reject {
            Objects.requireNonNull(reason, "reason");
            reason = reason.trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("reason 不能为空");
            }
        }
    }
}
