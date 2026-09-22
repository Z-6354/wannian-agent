package com.wannian.server.kernel.agent;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Loop 运行的脱敏步骤记录。
 *
 * <p>只记 step、decision 类型、耗时、usage 摘要、error code 等审计字段；不得含 API Key、SQL、堆栈或私密倾诉全文。
 *
 * @param steps 有序步骤摘要；null 视为空列表
 */
public record AgentTrace(List<String> steps) {

    public AgentTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 空 trace。 */
    public static AgentTrace empty() {
        return new AgentTrace(List.of());
    }

    /** 单步摘要；{@code step} 不得为 null。 */
    public static AgentTrace of(String step) {
        Objects.requireNonNull(step, "step");
        return new AgentTrace(List.of(step));
    }

    /** 由结构化步骤生成单步摘要（写入 {@link #steps} 的脱敏字符串）。 */
    public static AgentTrace ofStep(Step step) {
        Objects.requireNonNull(step, "step");
        return of(step.toSummary());
    }

    /**
     * 0.2.1 单步审计字段；无工具时可不填 tool / operationId。
     *
     * @param stepNumber Loop 内 decide 序号（从 1 起）
     * @param decisionType 如 FinalAnswer / ToolCalls / ModelRefusal / Failure
     * @param durationMs decide 耗时；未知时 null
     * @param usageSummary 如 {@code p=10,c=20}；无用量时 null
     * @param errorCode 失败时的稳定 code；成功时 null
     * @param softDeadlinePassed 软截止已过后的标注
     */
    public record Step(
            int stepNumber,
            String decisionType,
            Long durationMs,
            String usageSummary,
            String errorCode,
            boolean softDeadlinePassed) {

        public Step {
            Objects.requireNonNull(decisionType, "decisionType");
            decisionType = decisionType.trim();
            if (decisionType.isEmpty()) {
                throw new IllegalArgumentException("decisionType 不能为空");
            }
            if (usageSummary != null) {
                usageSummary = usageSummary.trim();
                if (usageSummary.isEmpty()) {
                    usageSummary = null;
                }
            }
            if (errorCode != null) {
                errorCode = errorCode.trim();
                if (errorCode.isEmpty()) {
                    errorCode = null;
                }
            }
        }

        /** 单行脱敏摘要。 */
        public String toSummary() {
            StringBuilder out = new StringBuilder(96);
            out.append("step=").append(stepNumber);
            out.append(";decision=").append(decisionType);
            if (durationMs != null) {
                out.append(";ms=").append(durationMs);
            }
            if (usageSummary != null) {
                out.append(";usage=").append(usageSummary);
            }
            if (errorCode != null) {
                out.append(";code=").append(errorCode);
            }
            if (softDeadlinePassed) {
                out.append(";软截止已过");
            }
            return out.toString();
        }
    }
}
