package com.wannian.server.kernel.journal;

import java.util.List;

/** 进程生命周期事件只读查询（0.2.3-L）。 */
public interface ProcessEventStore {

    /** 按时间倒序列出最近条目；limit 须为正。 */
    List<RunJournalEntry> listRecent(int limit);
}
