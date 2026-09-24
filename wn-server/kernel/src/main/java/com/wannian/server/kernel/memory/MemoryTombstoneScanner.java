package com.wannian.server.kernel.memory;

/**
 * 弱 B 扫墓 Port：确定性 score/age 规则 → FORGOTTEN；零 LLM。
 */
public interface MemoryTombstoneScanner {

    /**
     * 扫描并墓碑符合条件的 ACTIVE（可分页；失败记日志）。
     *
     * @return 本轮成功墓碑条数
     */
    int scanOnce();
}
