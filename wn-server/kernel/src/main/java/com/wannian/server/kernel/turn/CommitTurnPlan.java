package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import java.util.List;
import java.util.Objects;

/**
 * 完成 Turn 时一次性提交的不可变计划。
 *
 * <p>本批（K02 1B-3）覆盖「完成回合」最小切片：助手消息 + Turn 终态所需 revision + outbox。
 * Memory / Relationship / Task 仅占位：必须为空；非空应由实现返回 {@link CommitTurnResult.Rejected}，
 * 避免半吊子假提交。
 *
 * <p>接收 Turn（用户消息 + RECEIVED）不在本计划形状内，后续批次另议。
 */
public record CommitTurnPlan(
        TurnId turnId,
        long expectedTurnRevision,
        String expectedExecutionId,
        AssistantMessageDraft assistantMessage,
        List<OutboxEventDraft> outboxEvents,
        List<?> approvedMemoryChanges,
        Object approvedRelationshipChange,
        Object taskDraft) {

    /**
     * @param turnId 目标回合
     * @param expectedTurnRevision 乐观锁；与库中不一致则冲突。已完成回合的重试不靠这个字段再写一遍
     * @param expectedExecutionId 进入 COMMITTING 时冻结的那一次尝试；revision 对也不能代替它
     * @param assistantMessage 待写入的助手消息。序号由提交事务分配，草稿里的序号不是权威
     * @param outboxEvents 可选的附加事件。完成事件本身由提交器生成，空列表不等于可以没有完成事件
     * @param approvedMemoryChanges 占位；本批必须 empty
     * @param approvedRelationshipChange 占位；本批必须 null
     * @param taskDraft 占位；本批必须 null
     */
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
     * 构造本批支持的完成计划（Memory/Rel/Task 固定为空）。
     */
    public static CommitTurnPlan completeTurn(
            TurnId turnId,
            long expectedTurnRevision,
            String expectedExecutionId,
            AssistantMessageDraft assistantMessage,
            List<OutboxEventDraft> outboxEvents) {
        return new CommitTurnPlan(
                turnId,
                expectedTurnRevision,
                expectedExecutionId,
                assistantMessage,
                outboxEvents,
                List.of(),
                null,
                null);
    }

    /** 本批是否携带尚未支持的扩展变更（实现应拒绝）。 */
    public boolean hasUnsupportedExtensions() {
        return !approvedMemoryChanges.isEmpty()
                || approvedRelationshipChange != null
                || taskDraft != null;
    }

    /**
     * 待提交的助手消息草稿（非 DB 行对象）。
     *
     * @param messageId 应用层预生成的消息 id
     * @param role 应为 {@link MessageRole#ASSISTANT}
     * @param contentJson 版本化 JSON envelope 文本
     * @param sequenceNo 已弃用。提交事务会自行分配会话内序号，这个数字不会被写入
     */
    public record AssistantMessageDraft(
            MessageId messageId, MessageRole role, String contentJson, int sequenceNo) {

        public AssistantMessageDraft {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(contentJson, "contentJson");
        }
    }

    /**
     * 待追加的 outbox 事件草稿（非 DB 行对象）。
     *
     * @param eventId 应用层预生成的事件 id。完成事件的 id 由提交器生成，调用方 id 不作为完成事实
     * @param aggregateType 如 {@code turn}
     * @param aggregateId 通常为 turnId 字符串
     * @param eventType 如 {@code TurnCompleted}。错配的完成事件会被拒绝，不能靠一条无关事件蒙混
     * @param payloadJson 对外载荷 JSON；不得含敏感 trace
     * @param sequenceNo 已弃用。全局序号在写事务内分配，调用方数字不是游标权威
     */
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
