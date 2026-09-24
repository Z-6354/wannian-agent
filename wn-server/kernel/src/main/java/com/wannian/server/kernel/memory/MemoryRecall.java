package com.wannian.server.kernel.memory;

import java.time.Instant;
import java.util.List;

/**
 * 记忆召回 Port（S8-a-min：ACTIVE × {@link MemoryDecay#score} 排序截断；无向量）。
 *
 * <p>实现依赖只读 {@link MemoryStore} + {@link MemoryDecay}；禁止经本口写库。
 * 字数预算由 Assembler / 实现常数截断，不进本方法签名（本批）。
 */
public interface MemoryRecall {

    /**
     * 按衰减分召回 Top-N。
     *
     * <p>排序：score desc，同分 {@code createdAt} desc。只含 ACTIVE。
     *
     * @param companionIdentity 伴身；不得为 null
     * @param now 计分用「现在」；不得为 null
     * @param limit Top-N；≤0 时返回空列表
     * @return 不可变列表；不得为 null
     */
    List<StoredMemoryRecord> recallTop(
            CompanionIdentity companionIdentity, Instant now, int limit);
}
