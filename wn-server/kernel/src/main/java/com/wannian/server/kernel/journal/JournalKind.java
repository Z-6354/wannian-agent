package com.wannian.server.kernel.journal;

/** 行为账本 kind（0.2.3-L）。 */
public enum JournalKind {
    PROCESS_START,
    PROCESS_SHUTDOWN,
    USER_INPUT,
    /** 脱敏危机规则判定与安全响应路径；不得包含用户原文。 */
    CRISIS_DECISION,
    MODEL_CALL,
    TOOL_CALL,
    /** 正式 Memory 落库成功事实；仅由 Committer 事务写入，不经尽力运行观察通道冒充。 */
    MEMORY_WRITE,
    FINALIZE
}
