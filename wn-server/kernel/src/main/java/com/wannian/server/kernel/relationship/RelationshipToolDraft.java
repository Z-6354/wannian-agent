package com.wannian.server.kernel.relationship;

import com.wannian.server.kernel.memory.CompanionIdentity;
import java.util.Objects;

/**
 * 关系 tool 草案（S5-c-tool；Adapter 唯一产物；禁止写库）。
 *
 * <p>与记忆路径解耦：不得经 Memory* 类型改 relationship_state。
 */
public record RelationshipToolDraft(
        CompanionIdentity companionIdentity,
        String preferredAddress,
        String boundaries,
        String reason) {

    public RelationshipToolDraft {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(reason, "reason");
        reason = reason.trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("reason 不得空白");
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
}
