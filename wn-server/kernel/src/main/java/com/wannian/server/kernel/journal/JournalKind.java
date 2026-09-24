package com.wannian.server.kernel.journal;

/** 行为账本 kind（0.2.3-L）。 */
public enum JournalKind {
    PROCESS_START,
    PROCESS_SHUTDOWN,
    USER_INPUT,
    MODEL_CALL,
    TOOL_CALL,
    FINALIZE
}
