package com.wannian.server.kernel.memory;

import java.time.Instant;
import java.util.List;

/**
 * 召回注入成功后 bump {@code last_recalled_at}（best-effort；失败不挡 assemble）。
 *
 * <p>实现在 app；kernel Assembler 只依赖本口，可 null=关闭 bump。
 */
public interface MemoryRecallTouch {

    /**
     * @param ids 本轮注入的记忆 id；空则 no-op
     * @param now 写入时间
     */
    void touchRecalled(List<String> ids, Instant now);
}
