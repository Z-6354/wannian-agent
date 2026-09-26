package com.wannian.server.kernel.memory;

import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.util.List;
import java.util.Map;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/**
 * 本轮 Turn 的记忆/关系 pending 槽（热路径；禁止写库）。
 *
 * <p>tool 经 Shape+Policy ACCEPT 后 {@code add*}；Freeze 前 {@code snapshot*} 拷贝进计划。
 * 关系本批至多一条：{@code addRelationship} 覆盖前一条。
 * 记忆按 {@code subjectKey} 合并：后写覆盖先写，避免同 Turn 多条同 subject 在 commit 时自撞代次。
 */
public interface TurnMemoryPending {

    /** Identity frozen for this turn; legacy implementations retain yanhuo behavior. */
    default CompanionIdentity companionIdentity() { return CompanionIdentity.YANHUO; }
    /** Original user text, never model/tool output; used to gate extension write tools. */
    default String userMessage() { return null; }
    default ConversationId conversationId() { return null; }
    default TurnId turnId() { return null; }

    /** 同 {@code subjectKey} 后写覆盖先写（位置保留）。 */
    void addMemory(ApprovedMemoryChange change);

    void addRelationship(ApprovedRelationshipChange change);

    /** 不可变快照；实现可在 Turn 结束前一直保留（不必 clear）。 */
    List<ApprovedMemoryChange> snapshotMemories();

    /** Generation captured before the turn's model loop; -1 means the caller has no snapshot. */
    default long expectedGeneration(CompanionIdentity companion, String subjectKey) {
        return -1L;
    }

    default Map<String, Long> generationSnapshot() {
        return Map.of();
    }

    ApprovedRelationshipChange snapshotRelationshipOrNull();
}
