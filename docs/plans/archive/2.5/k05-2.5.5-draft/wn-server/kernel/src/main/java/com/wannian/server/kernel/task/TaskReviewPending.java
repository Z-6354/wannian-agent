package com.wannian.server.kernel.task;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.Objects;

/**
 * 用户待审后台提案快照（2.5.5）。
 *
 * <p>确认前<strong>不得</strong>插入 {@code background_task}。
 */
public record TaskReviewPending(
        TaskReviewId reviewId,
        ConversationId conversationId,
        TurnId turnId,
        TaskProposal proposal,
        String acknowledgementText,
        TaskReviewStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public TaskReviewPending {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(acknowledgementText, "acknowledgementText");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        acknowledgementText = acknowledgementText.trim();
        if (acknowledgementText.isEmpty()) {
            throw new IllegalArgumentException("acknowledgementText 不能为空");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt 须晚于 createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == TaskReviewStatus.PENDING && !now.isBefore(expiresAt);
    }
}
