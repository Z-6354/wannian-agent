package com.wannian.server.kernel.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆只读存储 Port。正式写入只经 TurnCommitter / MemoryCommand。
 */
public interface MemoryStore {

    Optional<StoredMemoryRecord> findById(String id);

    /** 指定伴身与生命周期的列表（如 GET ACTIVE）。 */
    List<StoredMemoryRecord> listByCompanion(
            CompanionIdentity companionIdentity, MemoryLifecycle lifecycle);

    /** 弱 B / 衰减扫描用：某伴身全部 ACTIVE（实现可分页，本批可一次取出上限内）。 */
    List<StoredMemoryRecord> listActive(CompanionIdentity companionIdentity);

    /** Snapshot all known subject generations before model execution. Missing subjects are generation 0. */
    default Map<String, Long> subjectGenerations(CompanionIdentity companionIdentity) {
        return Map.of();
    }

    /** Active rows and generation tokens observed from one storage snapshot. */
    default SubjectSnapshot subjectSnapshot(CompanionIdentity companionIdentity) {
        return new SubjectSnapshot(listActive(companionIdentity), subjectGenerations(companionIdentity));
    }

    record SubjectSnapshot(List<StoredMemoryRecord> active, Map<String, Long> generations) {
        public SubjectSnapshot {
            active = List.copyOf(active);
            generations = Map.copyOf(generations);
        }
    }
}
