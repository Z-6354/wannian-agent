package com.wannian.server.kernel.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 默认召回：ACTIVE × {@link MemoryDecay#score} 排序截断；无向量、不写库、不 bump。
 */
public final class DefaultMemoryRecall implements MemoryRecall {

    private final MemoryStore store;

    public DefaultMemoryRecall(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<StoredMemoryRecord> recallTop(
            CompanionIdentity companionIdentity, Instant now, int limit) {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            return List.of();
        }
        List<StoredMemoryRecord> active = store.listActive(companionIdentity);
        List<Scored> scored = new ArrayList<>(active.size());
        for (StoredMemoryRecord row : active) {
            Duration age = Duration.between(row.createdAt(), now);
            if (age.isNegative()) {
                age = Duration.ZERO;
            }
            scored.add(new Scored(row, MemoryDecay.score(row.importance(), age)));
        }
        scored.sort(
                Comparator.comparingDouble(Scored::score)
                        .reversed()
                        .thenComparing(s -> s.row().createdAt(), Comparator.reverseOrder()));
        int n = Math.min(limit, scored.size());
        List<StoredMemoryRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(scored.get(i).row());
        }
        return List.copyOf(out);
    }

    private record Scored(StoredMemoryRecord row, double score) {}
}
