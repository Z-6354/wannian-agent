package com.wannian.server.kernel.journal;

/**
 * 行为账本 kind。
 *
 * <p>0.2.3-L：进程启停与 Turn 运行观察（USER / MODEL / TOOL / FINALIZE）。
 * <p>0.2.4-A：正式记忆写入事实 {@link #MEMORY_WRITE}（与 Committer 同事务；非运行观察）。
 */
public enum JournalKind {
    PROCESS_START,
    PROCESS_SHUTDOWN,
    USER_INPUT,
    MODEL_CALL,
    TOOL_CALL,
    /** 正式 Memory 落库成功事实；仅由 Committer 事务写入，不经尽力运行观察通道冒充。 */
    MEMORY_WRITE,
    FINALIZE
}
