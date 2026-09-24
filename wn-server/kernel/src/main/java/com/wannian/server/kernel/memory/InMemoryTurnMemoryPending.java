package com.wannian.server.kernel.memory;

import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单回合 pending 默认实现（Turn 内串行使用；禁止跨 Turn 复用同一实例）。
 */
public final class InMemoryTurnMemoryPending implements TurnMemoryPending {

    private final List<ApprovedMemoryChange> memories = new ArrayList<>();
    private final Map<String, Long> generations;
    private final boolean hasGenerationSnapshot;
    private ApprovedRelationshipChange relationship;

    public InMemoryTurnMemoryPending() {
        this(Map.of(), false);
    }

    public InMemoryTurnMemoryPending(Map<String, Long> generations) {
        this(generations, true);
    }

    private InMemoryTurnMemoryPending(Map<String, Long> generations, boolean hasGenerationSnapshot) {
        this.generations = Map.copyOf(generations);
        this.hasGenerationSnapshot = hasGenerationSnapshot;
    }

    @Override
    public void addMemory(ApprovedMemoryChange change) {
        memories.add(Objects.requireNonNull(change, "change"));
    }

    @Override
    public void addRelationship(ApprovedRelationshipChange change) {
        relationship = Objects.requireNonNull(change, "change");
    }

    @Override
    public List<ApprovedMemoryChange> snapshotMemories() {
        return List.copyOf(memories);
    }

    @Override
    public long expectedGeneration(CompanionIdentity companion, String subjectKey) {
        return hasGenerationSnapshot ? generations.getOrDefault(subjectKey, 0L) : -1L;
    }

    @Override
    public Map<String, Long> generationSnapshot() {
        return generations;
    }

    @Override
    public ApprovedRelationshipChange snapshotRelationshipOrNull() {
        return relationship;
    }
}
