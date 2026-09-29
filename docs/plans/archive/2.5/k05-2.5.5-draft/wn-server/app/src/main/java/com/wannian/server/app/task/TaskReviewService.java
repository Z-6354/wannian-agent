package com.wannian.server.app.task;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskReviewPending;
import com.wannian.server.kernel.task.TaskReviewPendingRepository;
import com.wannian.server.kernel.task.TaskReviewStatus;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.TurnEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户审核确认/驳回（2.5.5）。
 *
 * <p>确认：CAS → prepare → {@link TurnEngine#commitTaskReviewAcceptance}（带 Draft）。
 * 驳回：CAS → 不插 Task。
 */
public final class TaskReviewService {

    private final TaskReviewPendingRepository reviews;
    private final TaskRuntime taskRuntime;
    private final TurnEngine turnEngine;
    private final Clock clock;
    private final Duration claimLease;

    public TaskReviewService(
            TaskReviewPendingRepository reviews,
            TaskRuntime taskRuntime,
            TurnEngine turnEngine,
            Clock clock,
            Duration claimLease) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.turnEngine = Objects.requireNonNull(turnEngine, "turnEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease 须为正");
        }
    }

    public List<TaskReviewPending> listPending(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Instant now = clock.instant();
        List<TaskReviewPending> rows =
                reviews.listByConversation(conversationId, TaskReviewStatus.PENDING);
        for (TaskReviewPending row : rows) {
            if (row.isExpired(now)) {
                reviews.casStatus(
                        row.reviewId(),
                        TaskReviewStatus.PENDING,
                        TaskReviewStatus.EXPIRED,
                        now);
            }
        }
        return reviews.listByConversation(conversationId, TaskReviewStatus.PENDING);
    }

    public ReviewActionResult confirm(ConversationId conversationId, TaskReviewId reviewId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Instant now = clock.instant();
        Optional<TaskReviewPending> found = reviews.findById(reviewId);
        if (found.isEmpty()) {
            return ReviewActionResult.failed(
                    ErrorCodes.TASK_REVIEW_NOT_FOUND, "待审单不存在");
        }
        TaskReviewPending pending = found.get();
        if (!pending.conversationId().equals(conversationId)) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_ARGUMENT, "待审单不属于该会话");
        }
        if (pending.status() != TaskReviewStatus.PENDING) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态为 " + pending.status());
        }
        if (pending.isExpired(now)) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.EXPIRED, now);
            return ReviewActionResult.failed(
                    ErrorCodes.TASK_REVIEW_EXPIRED, "待审单已过期");
        }
        if (!reviews.casStatus(
                reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.CONFIRMED, now)) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态已变更");
        }
        TaskDraft draft;
        try {
            draft = taskRuntime.prepare(pending.proposal());
        } catch (RuntimeException ex) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CONFIRMED, TaskReviewStatus.PENDING, now);
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    ex.getMessage() == null ? "prepare 失败" : ex.getMessage());
        }
        ExecuteTurnResult sealed =
                turnEngine.commitTaskReviewAcceptance(
                        conversationId,
                        "task-review-confirm:" + reviewId.asString(),
                        pending.acknowledgementText(),
                        draft,
                        claimLease);
        if (sealed instanceof ExecuteTurnResult.Replied replied) {
            return ReviewActionResult.ok(replied.text(), draft.taskId().asString());
        }
        // commit 未成功：回滚 CONFIRMED→PENDING，避免砖化待审单且无 background_task
        reviews.casStatus(
                reviewId, TaskReviewStatus.CONFIRMED, TaskReviewStatus.PENDING, now);
        if (sealed instanceof ExecuteTurnResult.Held held) {
            return ReviewActionResult.failed(held.code(), held.detail());
        }
        return ReviewActionResult.failed(ErrorCodes.COMMIT_FAILED, "确认提交失败");
    }

    public ReviewActionResult reject(
            ConversationId conversationId, TaskReviewId reviewId, String reason) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Instant now = clock.instant();
        Optional<TaskReviewPending> found = reviews.findById(reviewId);
        if (found.isEmpty()) {
            return ReviewActionResult.failed(
                    ErrorCodes.TASK_REVIEW_NOT_FOUND, "待审单不存在");
        }
        TaskReviewPending pending = found.get();
        if (!pending.conversationId().equals(conversationId)) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_ARGUMENT, "待审单不属于该会话");
        }
        if (pending.status() != TaskReviewStatus.PENDING) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态为 " + pending.status());
        }
        if (pending.isExpired(now)) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.EXPIRED, now);
            return ReviewActionResult.failed(
                    ErrorCodes.TASK_REVIEW_EXPIRED, "待审单已过期");
        }
        if (!reviews.casStatus(
                reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.REJECTED, now)) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态已变更");
        }
        String message =
                reason == null || reason.isBlank()
                        ? "已取消创建任务"
                        : "已取消创建任务：" + reason.trim();
        return ReviewActionResult.ok(message, null);
    }

    public record ReviewActionResult(boolean success, String code, String detail, String taskId) {
        public static ReviewActionResult ok(String detail, String taskId) {
            return new ReviewActionResult(true, null, detail, taskId);
        }

        public static ReviewActionResult failed(String code, String detail) {
            return new ReviewActionResult(false, code, detail, null);
        }
    }
}
