package com.wannian.server.kernel.memory;

import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/**
 * 单回合 pending 默认实现（Turn 内串行使用；禁止跨 Turn 复用同一实例）。
 *
 * <p>同 {@code subjectKey} 后写覆盖先写，避免 commit 时自撞 generation。
 */
public final class InMemoryTurnMemoryPending implements TurnMemoryPending {

    private final List<ApprovedMemoryChange> memories = new ArrayList<>();
    private final Map<String, Long> generations;
    private final boolean hasGenerationSnapshot;
    private final CompanionIdentity companionIdentity;
    private final String userMessage;
    private final ConversationId conversationId;
    private final TurnId turnId;
    private ApprovedRelationshipChange relationship;

    public InMemoryTurnMemoryPending() {
        this(Map.of(), false, CompanionIdentity.YANHUO, null, null, null);
    }

    public InMemoryTurnMemoryPending(Map<String, Long> generations) {
        this(generations, true, CompanionIdentity.YANHUO, null, null, null);
    }

    public InMemoryTurnMemoryPending(Map<String, Long> generations, CompanionIdentity companionIdentity) {
        this(generations, true, companionIdentity, null, null, null);
    }

    public InMemoryTurnMemoryPending(Map<String, Long> generations, CompanionIdentity companionIdentity, String userMessage) {
        this(generations, true, companionIdentity, userMessage, null, null);
    }

    public InMemoryTurnMemoryPending(Map<String, Long> generations, CompanionIdentity companionIdentity, String userMessage,
            ConversationId conversationId, TurnId turnId) {
        this(generations, true, companionIdentity, userMessage, conversationId, turnId);
    }

    private InMemoryTurnMemoryPending(Map<String, Long> generations, boolean hasGenerationSnapshot, CompanionIdentity companionIdentity, String userMessage,
            ConversationId conversationId, TurnId turnId) {
        this.generations = Map.copyOf(generations);
        this.hasGenerationSnapshot = hasGenerationSnapshot;
        this.companionIdentity = Objects.requireNonNull(companionIdentity);
        this.userMessage = userMessage;
        this.conversationId=conversationId;
        this.turnId=turnId;
    }

    @Override public CompanionIdentity companionIdentity() { return companionIdentity; }
    @Override public String userMessage() { return userMessage; }
    @Override public ConversationId conversationId() { return conversationId; }
    @Override public TurnId turnId() { return turnId; }

    @Override
    public void addMemory(ApprovedMemoryChange change) {
        Objects.requireNonNull(change, "change");
        String subjectKey = change.subjectKey();
        for (int i = 0; i < memories.size(); i++) {
            if (memories.get(i).subjectKey().equals(subjectKey)) {
                memories.set(i, change);
                return;
            }
        }
        memories.add(change);
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
