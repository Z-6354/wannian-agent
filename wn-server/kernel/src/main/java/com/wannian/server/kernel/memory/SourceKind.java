package com.wannian.server.kernel.memory;

/**
 * 记忆来源种类（证据轴；与 {@link ContentKind} 正交）。
 *
 * <p>本批 tool/review 默认 {@link #EXPLICIT} 或 {@link #OBSERVED}；
 * {@link #INFERRED} 不因 S4-min 强制拒。升档规则见施工单注明，本批不实现全量提升机。
 */
public enum SourceKind {
    /** 用户明示记住 / 明确陈述。 */
    EXPLICIT,
    /** 对话中可观察的明确事实（非推断）。 */
    OBSERVED,
    /** 由多证据归纳；本批不强制拒。 */
    INFERRED,
    /** 反思/整理产物；不得盖过明示用户陈述（产品边界）。 */
    REFLECTION
}
