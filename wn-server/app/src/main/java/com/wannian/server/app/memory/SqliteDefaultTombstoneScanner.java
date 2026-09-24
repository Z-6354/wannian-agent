package com.wannian.server.app.memory;

import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryDecay;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.MemoryTombstoneScanner;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 弱 B 确定性扫墓（S12-weak-B）。
 *
 * <p><b>行为</b>
 *
 * <ul>
 *   <li>读 {@link MemoryStore#listActive}（仅 yanhuo）→ {@link MemoryDecay#shouldTombstone}
 *       （A+B 两条款）→ 经 {@link MemoryCommand#tombstone} 短事务 CAS；<strong>零 LLM</strong>。
 *   <li>单次 {@link #scanOnce} 最多尝试 {@code batchLimit} 个墓碑候选（yml {@code
 *       wannian.memory.tombstone.batch-limit}）。先应用纯 {@link MemoryDecay#shouldTombstone}
 *       过滤，再限制 CAS 尝试数，避免 ACTIVE 列表按新到旧排序时旧候选被新记录永久挡住。
 *   <li>单条 Rejected 只打 WARNING 并继续；不挡 Review tick / 聊天。
 *   <li>不经 {@code memory_review_job} 表；由 {@link MemoryReviewConfig} 在 tick 末尾调用。
 * </ul>
 */
public final class SqliteDefaultTombstoneScanner implements MemoryTombstoneScanner {

    private static final System.Logger LOG =
            System.getLogger(SqliteDefaultTombstoneScanner.class.getName());

    private final MemoryStore memoryStore;
    private final MemoryCommand memoryCommand;
    private final Clock clock;
    /** 单 tick 最多处理墓碑候选数（Rejected 也计入，继续尝试下一候选）。 */
    private final int batchLimit;

    public SqliteDefaultTombstoneScanner(
            MemoryStore memoryStore, MemoryCommand memoryCommand, int batchLimit) {
        this(memoryStore, memoryCommand, Clock.systemUTC(), batchLimit);
    }

    public SqliteDefaultTombstoneScanner(
            MemoryStore memoryStore, MemoryCommand memoryCommand, Clock clock, int batchLimit) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.memoryCommand = Objects.requireNonNull(memoryCommand, "memoryCommand");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchLimit < 1) {
            throw new IllegalArgumentException("batchLimit 须 >= 1");
        }
        this.batchLimit = batchLimit;
    }

    /**
     * @return 本轮成功 Applied 的条数（非考察条数）
     */
    @Override
    public int scanOnce() {
        Instant now = Instant.now(clock);
        List<StoredMemoryRecord> active = memoryStore.listActive(CompanionIdentity.YANHUO);
        List<StoredMemoryRecord> candidates =
                active.stream()
                        .filter(
                                row -> {
                                    Duration age = Duration.between(row.createdAt(), now);
                                    return MemoryDecay.shouldTombstone(row.importance(), age);
                                })
                        .sorted(
                                Comparator.comparing(StoredMemoryRecord::createdAt)
                                        .thenComparing(StoredMemoryRecord::id))
                        .toList();
        int success = 0;
        int attempted = 0;
        for (StoredMemoryRecord row : candidates) {
            if (attempted >= batchLimit) {
                break;
            }
            attempted++;
            MemoryCommand.CommandResult result =
                    memoryCommand.tombstone(row.id(), row.revision());
            if (result instanceof MemoryCommand.CommandResult.Applied) {
                success++;
            } else if (result instanceof MemoryCommand.CommandResult.Rejected rejected) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () ->
                                "tombstone 跳过 id="
                                        + row.id()
                                        + " code="
                                        + rejected.code()
                                        + " "
                                        + rejected.message());
            }
        }
        return success;
    }
}
