package com.wannian.server.kernel.relationship;

import com.wannian.server.kernel.memory.CompanionIdentity;
import java.time.Instant;
import java.util.Objects;

/**
 * 已落库关系状态的只读投影（RelationshipStore 查询结果；禁止当写缝入参）。
 */
public record StoredRelationshipState(
        CompanionIdentity companionIdentity,
        String preferredAddress,
        String boundaries,
        String reason,
        long revision,
        Instant updatedAt,
        String sourceTurnId) {

    public StoredRelationshipState {
        Objects.requireNonNull(companionIdentity, "companionIdentity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(updatedAt, "updatedAt");
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
        if (sourceTurnId != null) {
            sourceTurnId = sourceTurnId.trim();
            if (sourceTurnId.isEmpty()) {
                sourceTurnId = null;
            }
        }
    }

    /** Assembler / beforeTurn 用的短文本；无称呼且无边界时返回空串。 */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (preferredAddress != null) {
            sb.append("称呼：").append(preferredAddress);
        }
        if (boundaries != null) {
            if (!sb.isEmpty()) {
                sb.append('；');
            }
            sb.append("边界：").append(boundaries);
        }
        return sb.toString();
    }
}
