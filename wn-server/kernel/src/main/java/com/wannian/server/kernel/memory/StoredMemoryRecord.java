package com.wannian.server.kernel.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 已落库记忆的只读投影（MemoryStore 查询结果；禁止当写缝入参）。
 */
public record StoredMemoryRecord(
        String id,
        CompanionIdentity companionIdentity,
        String subjectKey,
        String claim,
        ContentKind contentKind,
        SourceKind sourceKind,
        MemoryScope scope,
        double importance,
        String path,
        MemoryLifecycle lifecycle,
        String proposeId,
        long revision,
        Instant createdAt,
        Instant lastRecalledAt,
        String supersedesId) {

    public StoredMemoryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(contentKind, "contentKind");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(proposeId, "proposeId");
        Objects.requireNonNull(createdAt, "createdAt");
        id = id.trim();
        subjectKey = subjectKey.trim();
        claim = claim.trim();
        proposeId = proposeId.trim();
        if (id.isEmpty() || subjectKey.isEmpty() || proposeId.isEmpty()) {
            throw new IllegalArgumentException("id/subjectKey/proposeId 不得空白");
        }
        // forget/tombstone 清空正文；非 ACTIVE 历史记录仍可按 id 或 lifecycle 查询。
        // ACTIVE claim 保持非空，避免空内容进入召回与上下文装配。
        if (claim.isEmpty() && lifecycle == MemoryLifecycle.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE 记忆 claim 不得空白");
        }
        if (path != null) {
            path = path.trim();
            if (path.isEmpty()) {
                path = null;
            }
        }
        if (supersedesId != null) {
            supersedesId = supersedesId.trim();
            if (supersedesId.isEmpty()) {
                supersedesId = null;
            }
        }
        if (Double.isNaN(importance) || Double.isInfinite(importance)) {
            throw new IllegalArgumentException("importance 必须为有限数值");
        }
    }
}
