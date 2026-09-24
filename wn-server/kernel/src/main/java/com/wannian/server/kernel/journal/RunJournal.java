package com.wannian.server.kernel.journal;

/**
 * 行为账本写入口（0.2.3-L）。
 *
 * <p>实现可组合 JSONL 与 SQLite；失败须吞掉并记运维日志，不得抛回打断 Turn。
 * kernel 只依赖本接口，不依赖具体日志框架。
 */
public interface RunJournal {

    /** 追加一条；不得为 null。 */
    void append(RunJournalEntry entry);

    /** 空实现（单测 / 未启用）。 */
    static RunJournal noop() {
        return entry -> {
            // intentionally empty
        };
    }
}
