package com.wannian.server.kernel.relationship;

import com.wannian.server.kernel.memory.CompanionIdentity;
import java.util.Objects;

/**
 * 已批准的关系变更（不可变；仅经必要校验后构造）。
 *
 * <p>热路径进 Freeze；冷路径短事务。禁止再改字段后重放。
 */
public record ApprovedRelationshipChange(
        CompanionIdentity companionIdentity,
        String preferredAddress,
        String boundaries,
        String reason,
        String proposeId) {

    public static final String PROPOSE_TOOL_RELATIONSHIP = "tool_relationship";

    public ApprovedRelationshipChange {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(proposeId, "proposeId");
        reason = reason.trim();
        proposeId = proposeId.trim();
        if (reason.isEmpty() || proposeId.isEmpty()) {
            throw new IllegalArgumentException("reason/proposeId 不得空白");
        }
        if (preferredAddress != null) {
            preferredAddress = preferredAddress.trim();
            if (preferredAddress.isEmpty()) {
                preferredAddress = null;
            }
        }
        if (boundaries != null) {
            boundaries = boundaries.trim();
            if (boundaries.isEmpty()) {
                boundaries = null;
            }
        }
        if (preferredAddress == null && boundaries == null) {
            throw new IllegalArgumentException("须至少提供 preferredAddress 或 boundaries");
        }
    }

    public static ApprovedRelationshipChange fromToolDraft(RelationshipToolDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new ApprovedRelationshipChange(
                draft.companionIdentity(),
                draft.preferredAddress(),
                draft.boundaries(),
                draft.reason(),
                PROPOSE_TOOL_RELATIONSHIP);
    }
}
