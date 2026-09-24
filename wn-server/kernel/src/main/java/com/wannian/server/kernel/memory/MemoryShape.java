package com.wannian.server.kernel.memory;

/**
 * 记忆 Shape / Validate（S3-ca：本批仅钳制 {@code importance}；零额外 LLM）。
 *
 * <p>claim 规范化（相对时间→日历日、指代→具体地点）由宿主注入锚 + 模型同一次输出完成，
 * <strong>本口不做</strong>夹具词拒写、也不正则改写「今天」。禁止经本口写库。
 */
public interface MemoryShape {

    ShapeResult shape(MemoryToolDraft draft);

    /** Shape 封闭结果。 */
    sealed interface ShapeResult {

        /** 通过；{@code importance} 已钳制到 [0,1]。 */
        record Accepted(MemoryToolDraft shaped) implements ShapeResult {}

        /** 拒绝；不进 Policy / 不落库（本批默认实现通常不走此支）。 */
        record Rejected(String code, String message) implements ShapeResult {}
    }
}
