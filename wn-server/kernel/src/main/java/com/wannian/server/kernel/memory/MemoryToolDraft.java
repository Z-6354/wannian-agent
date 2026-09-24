package com.wannian.server.kernel.memory;

import java.util.Objects;

/**
 * 记忆 tool 草案（B 轨 Adapter 唯一产物；禁止写库）。
 *
 * <p>须含规范化 {@link #claim} 与模型 {@link #importance}。Shape / Policy 之后才进 Approved* → Freeze。
 */
public record MemoryToolDraft(
        CompanionIdentity companionIdentity,
        String subjectKey,
        String claim,
        ContentKind contentKind,
        SourceKind sourceKind,
        MemoryScope scope,
        double importance,
        String path) {

    public MemoryToolDraft {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(contentKind, "contentKind");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(scope, "scope");
        subjectKey = subjectKey.trim();
        claim = claim.trim();
        if (subjectKey.isEmpty()) {
            throw new IllegalArgumentException("subjectKey 不得空白");
        }
        if (claim.isEmpty()) {
            throw new IllegalArgumentException("claim 不得空白");
        }
        if (path != null) {
            path = path.trim();
            if (path.isEmpty()) {
                path = null;
            }
        }
        // importance 范围由 Shape 裁决；此处只拒绝 NaN/Infinite，避免脏值进管道
        if (Double.isNaN(importance) || Double.isInfinite(importance)) {
            throw new IllegalArgumentException("importance 必须为有限数值");
        }
    }
}
