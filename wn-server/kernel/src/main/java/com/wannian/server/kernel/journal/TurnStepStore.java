package com.wannian.server.kernel.journal;

import java.util.List;

/** 按 turn 只读列出行为账本步骤（0.2.3-L）。 */
public interface TurnStepStore {

    /** @param turnId 回合 id；不得为 null */
    List<RunJournalEntry> listByTurnId(String turnId);
}
