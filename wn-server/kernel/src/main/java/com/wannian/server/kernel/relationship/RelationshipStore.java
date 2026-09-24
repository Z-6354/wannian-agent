package com.wannian.server.kernel.relationship;

import com.wannian.server.kernel.memory.CompanionIdentity;
import java.util.Optional;

/**
 * 关系状态只读存储 Port。正式写入只经 TurnCommitter / 短事务 Command。
 */
public interface RelationshipStore {

    Optional<StoredRelationshipState> findByCompanion(CompanionIdentity companionIdentity);
}
