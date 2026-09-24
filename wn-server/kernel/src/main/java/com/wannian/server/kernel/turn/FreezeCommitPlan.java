package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 进入 {@code COMMITTING} 时一次性冻结的最终提交事实。
 *
 * <p>与状态迁移同事务落库。恢复时只能提交这份内容，不能换一份答案或重跑模型。
 * 须显式携带记忆列表（可 empty）与可选关系变更。
 */
public record FreezeCommitPlan(
        TurnId turnId,
        long expectedTurnRevision,
        String expectedExecutionId,
        Instant now,
        CommitTurnPlan.AssistantMessageDraft assistantMessage,
        List<CommitTurnPlan.OutboxEventDraft> additionalOutboxEvents,
        List<ApprovedMemoryChange> approvedMemoryChanges,
        ApprovedRelationshipChange approvedRelationshipChange) {

    public FreezeCommitPlan {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(expectedExecutionId, "expectedExecutionId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        Objects.requireNonNull(additionalOutboxEvents, "additionalOutboxEvents");
        Objects.requireNonNull(approvedMemoryChanges, "approvedMemoryChanges");
        expectedExecutionId = expectedExecutionId.trim();
        if (expectedExecutionId.isEmpty()) {
            throw new IllegalArgumentException("expectedExecutionId 不能为空");
        }
        additionalOutboxEvents = List.copyOf(additionalOutboxEvents);
        approvedMemoryChanges = List.copyOf(approvedMemoryChanges);
    }

    public static FreezeCommitPlan of(
            TurnId turnId,
            long expectedTurnRevision,
            String expectedExecutionId,
            Instant now,
            CommitTurnPlan.AssistantMessageDraft assistantMessage,
            List<CommitTurnPlan.OutboxEventDraft> additionalOutboxEvents,
            List<ApprovedMemoryChange> approvedMemoryChanges,
            ApprovedRelationshipChange approvedRelationshipChange) {
        return new FreezeCommitPlan(
                turnId,
                expectedTurnRevision,
                expectedExecutionId,
                now,
                assistantMessage,
                additionalOutboxEvents,
                approvedMemoryChanges,
                approvedRelationshipChange);
    }
}
