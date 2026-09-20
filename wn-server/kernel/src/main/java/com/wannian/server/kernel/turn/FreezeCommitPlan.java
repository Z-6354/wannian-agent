package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 进入 {@code COMMITTING} 时一次性冻结的最终提交事实。
 *
 * <p>与状态迁移同事务落库。恢复时只能提交这份内容，不能换一份答案或重跑模型。
 */
public record FreezeCommitPlan(
        TurnId turnId,
        long expectedTurnRevision,
        String expectedExecutionId,
        Instant now,
        CommitTurnPlan.AssistantMessageDraft assistantMessage,
        List<CommitTurnPlan.OutboxEventDraft> additionalOutboxEvents) {

    public FreezeCommitPlan {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(expectedExecutionId, "expectedExecutionId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        Objects.requireNonNull(additionalOutboxEvents, "additionalOutboxEvents");
        expectedExecutionId = expectedExecutionId.trim();
        if (expectedExecutionId.isEmpty()) {
            throw new IllegalArgumentException("expectedExecutionId 不能为空");
        }
        additionalOutboxEvents = List.copyOf(additionalOutboxEvents);
    }

    public static FreezeCommitPlan of(
            TurnId turnId,
            long expectedTurnRevision,
            String expectedExecutionId,
            Instant now,
            CommitTurnPlan.AssistantMessageDraft assistantMessage) {
        return new FreezeCommitPlan(
                turnId, expectedTurnRevision, expectedExecutionId, now, assistantMessage, List.of());
    }
}
