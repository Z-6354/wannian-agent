package com.wannian.server.kernel.memory;

/**
 * 记忆作用域（落库 scope）。
 *
 * <p>本批默认 {@link #COMPANION}。与 {@link ContentKind} 正交。
 */
public enum MemoryScope {
    /** 伴身级长期记忆（跨会话）。 */
    COMPANION,
    /** 仅当前会话。 */
    CONVERSATION,
    /** 任务级；本批 task 扩展仍 UNSUPPORTED，枚举预留。 */
    TASK
}
