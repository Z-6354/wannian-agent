package com.wannian.server.kernel.memory;

import java.util.Objects;

/**
 * 已批准的记忆变更（不可变；仅经 Shape+Policy ACCEPT 后构造）。
 *
 * <p>热路径进入 Freeze 同事务；冷路径经 MemoryCommand 短事务。禁止再改字段后重放。
 */
public record ApprovedMemoryChange(
        CompanionIdentity companionIdentity,
        String subjectKey,
        String claim,
        ContentKind contentKind,
        SourceKind sourceKind,
        MemoryScope scope,
        double importance,
        String path,
        String proposeId,
        Long expectedGeneration) {

    public static final String PROPOSE_TOOL_REMEMBER = "tool_remember";
    public static final String PROPOSE_LLM_REVIEW = "llm_review";
    /** HTTP correct 新行 proposeId。 */
    public static final String PROPOSE_HTTP_CORRECT = "http_correct";
    /** 弱 B / 文档对齐；tombstone 状态迁移可不改 propose_id 列。 */
    public static final String PROPOSE_TOMBSTONE_SCAN = "tombstone_scan";

    /** Source compatibility for callers that create a change before a request snapshot exists. */
    public ApprovedMemoryChange(
            CompanionIdentity companionIdentity,
            String subjectKey,
            String claim,
            ContentKind contentKind,
            SourceKind sourceKind,
            MemoryScope scope,
            double importance,
            String path,
            String proposeId) {
        this(companionIdentity, subjectKey, claim, contentKind, sourceKind, scope, importance,
                path, proposeId, -1L);
    }

    public ApprovedMemoryChange {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(contentKind, "contentKind");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(proposeId, "proposeId");
        if (expectedGeneration != null && expectedGeneration < -1L) {
            throw new IllegalArgumentException("expectedGeneration 不得小于 -1");
        }
        subjectKey = subjectKey.trim();
        claim = claim.trim();
        proposeId = proposeId.trim();
        if (subjectKey.isEmpty()) {
            throw new IllegalArgumentException("subjectKey 不得空白");
        }
        if (claim.isEmpty()) {
            throw new IllegalArgumentException("claim 不得空白");
        }
        if (proposeId.isEmpty()) {
            throw new IllegalArgumentException("proposeId 不得空白");
        }
        if (path != null) {
            path = path.trim();
            if (path.isEmpty()) {
                path = null;
            }
        }
        if (Double.isNaN(importance) || Double.isInfinite(importance)) {
            throw new IllegalArgumentException("importance 必须为有限数值");
        }
    }

    /** Attach the subject generation captured before model/tool execution. */
    public ApprovedMemoryChange withExpectedGeneration(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation 不得为负数");
        }
        return new ApprovedMemoryChange(companionIdentity, subjectKey, claim, contentKind,
                sourceKind, scope, importance, path, proposeId, generation);
    }

    /** 由已通过校验的 tool 草案构造（proposeId=tool_remember）。 */
    public static ApprovedMemoryChange fromToolDraft(MemoryToolDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new ApprovedMemoryChange(
                draft.companionIdentity(),
                draft.subjectKey(),
                draft.claim(),
                draft.contentKind(),
                draft.sourceKind(),
                draft.scope(),
                draft.importance(),
                draft.path(),
                PROPOSE_TOOL_REMEMBER);
    }

    /** 由已通过校验的 Review 草案构造（proposeId=llm_review）。 */
    public static ApprovedMemoryChange fromReviewDraft(MemoryToolDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new ApprovedMemoryChange(
                draft.companionIdentity(),
                draft.subjectKey(),
                draft.claim(),
                draft.contentKind(),
                draft.sourceKind(),
                draft.scope(),
                draft.importance(),
                draft.path(),
                PROPOSE_LLM_REVIEW);
    }
}
