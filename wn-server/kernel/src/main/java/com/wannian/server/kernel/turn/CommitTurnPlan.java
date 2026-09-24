package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import java.util.List;
import java.util.Objects;

/**
 * 完成 Turn 时一次性提交的不可变计划。
 *
 * <p>0.2.3：可携带已批准的记忆与可选关系变更，由 {@code TurnCommitter} 同事务落库。
 * {@code taskDraft} 仍为占位；非空应由实现返回 {@link CommitTurnResult.Rejected}。
 */
public record CommitTurnPlan(
        TurnId turnId,
        long expectedTurnRevision,
        String expectedExecutionId,
        AssistantMessageDraft assistantMessage,
        List<OutboxEventDraft> outboxEvents,
        List<ApprovedMemoryChange> approvedMemoryChanges,
        ApprovedRelationshipChange approvedRelationshipChange,
        Object taskDraft) {

    public CommitTurnPlan {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(expectedExecutionId, "expectedExecutionId");
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        Objects.requireNonNull(outboxEvents, "outboxEvents");
        Objects.requireNonNull(approvedMemoryChanges, "approvedMemoryChanges");
        expectedExecutionId = expectedExecutionId.trim();
        if (expectedExecutionId.isEmpty()) {
            throw new IllegalArgumentException("expectedExecutionId 不能为空");
        }
        outboxEvents = List.copyOf(outboxEvents);
        approvedMemoryChanges = List.copyOf(approvedMemoryChanges);
    }

    /**
     * 完成计划（记忆可 empty；关系可 null；task 固定 null）。
     */
    public static CommitTurnPlan completeTurn(
            TurnId turnId,
            long expectedTurnRevision,
            String expectedExecutionId,
            AssistantMessageDraft assistantMessage,
            List<OutboxEventDraft> outboxEvents,
            List<ApprovedMemoryChange> approvedMemoryChanges,
            ApprovedRelationshipChange approvedRelationshipChange) {
        return new CommitTurnPlan(
                turnId,
                expectedTurnRevision,
                expectedExecutionId,
                assistantMessage,
                outboxEvents,
                approvedMemoryChanges,
                approvedRelationshipChange,
                null);
    }

    /** 是否携带尚未支持的扩展（本批仅 Task）。 */
    public boolean hasUnsupportedExtensions() {
        return taskDraft != null;
    }

    public record AssistantMessageDraft(
            MessageId messageId, MessageRole role, String contentJson, int sequenceNo) {

        public AssistantMessageDraft {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(contentJson, "contentJson");
        }
    }

    public record OutboxEventDraft(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            long sequenceNo) {

        public OutboxEventDraft {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(aggregateType, "aggregateType");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }
}
