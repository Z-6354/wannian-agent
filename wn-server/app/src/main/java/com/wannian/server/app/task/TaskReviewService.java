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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 用户审核确认/驳回（2.5.5）。
 *
 * <p>确认：{@code PENDING→CLAIMED} → prepare → {@link TurnEngine#commitTaskReviewAcceptance}
 * → {@code CLAIMED→CONFIRMED}。稳定幂等键 {@code task-review-confirm:{reviewId}}；
 * 启动对账收口孤儿 CLAIMED。
 *
 * <p>{@link TurnEngine} 经 {@link ObjectProvider} 延迟取用，避免对 final 类做 {@code @Lazy} CGLIB 代理。
 */
public final class TaskReviewService {

    private static final System.Logger LOG = System.getLogger(TaskReviewService.class.getName());

    private final TaskReviewPendingRepository reviews;
    private final TaskRuntime taskRuntime;
    private final ObjectProvider<TurnEngine> turnEngine;
    private final DataSource dataSource;
    private final Clock clock;
    private final Duration claimLease;

    public TaskReviewService(
            TaskReviewPendingRepository reviews,
            TaskRuntime taskRuntime,
            ObjectProvider<TurnEngine> turnEngine,
            DataSource dataSource,
            Clock clock,
            Duration claimLease) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.turnEngine = Objects.requireNonNull(turnEngine, "turnEngine");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
        if (pending.status() == TaskReviewStatus.CLAIMED) {
            Optional<ReviewActionResult> recovered = tryCompleteFromExistingTurn(pending, now);
            if (recovered.isPresent()) {
                return recovered.get();
            }
            // 无已提交 Turn：继续占用并重试提交
        } else if (pending.status() != TaskReviewStatus.PENDING) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态为 " + pending.status());
        } else if (pending.isExpired(now)) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.EXPIRED, now);
            return ReviewActionResult.failed(
                    ErrorCodes.TASK_REVIEW_EXPIRED, "待审单已过期");
        } else if (!reviews.casStatus(
                reviewId, TaskReviewStatus.PENDING, TaskReviewStatus.CLAIMED, now)) {
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_STATUS, "待审单状态已变更");
        }

        String clientRequestId = confirmClientRequestId(reviewId);
        Optional<ReviewActionResult> already = lookupCompletedConfirm(clientRequestId);
        if (already.isPresent()) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.CONFIRMED, now);
            return already.get();
        }

        TaskDraft draft;
        try {
            draft = taskRuntime.prepare(pending.proposal());
        } catch (RuntimeException ex) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.PENDING, now);
            return ReviewActionResult.failed(
                    ErrorCodes.ILLEGAL_ARGUMENT,
                    ex.getMessage() == null ? "prepare 失败" : ex.getMessage());
        }
        ExecuteTurnResult sealed;
        try {
            sealed =
                    turnEngine
                            .getObject()
                            .commitTaskReviewAcceptance(
                                    conversationId,
                                    clientRequestId,
                                    pending.acknowledgementText(),
                                    draft,
                                    claimLease);
        } catch (RuntimeException ex) {
            Optional<ReviewActionResult> afterCrash = lookupCompletedConfirm(clientRequestId);
            if (afterCrash.isPresent()) {
                reviews.casStatus(
                        reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.CONFIRMED, now);
                return afterCrash.get();
            }
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.PENDING, now);
            return ReviewActionResult.failed(
                    ErrorCodes.COMMIT_FAILED,
                    ex.getMessage() == null ? "确认提交异常" : ex.getMessage());
        }
        if (sealed instanceof ExecuteTurnResult.Replied replied) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.CONFIRMED, now);
            return ReviewActionResult.ok(replied.text(), draft.taskId().asString());
        }
        Optional<ReviewActionResult> raced = lookupCompletedConfirm(clientRequestId);
        if (raced.isPresent()) {
            reviews.casStatus(
                    reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.CONFIRMED, now);
            return raced.get();
        }
        reviews.casStatus(
                reviewId, TaskReviewStatus.CLAIMED, TaskReviewStatus.PENDING, now);
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
                        ? "已拒绝该后台任务。"
                        : "已拒绝该后台任务：" + reason.trim();
        ExecuteTurnResult sealed =
                turnEngine
                        .getObject()
                        .commitTaskReviewRejection(
                                conversationId,
                                "task-review-reject:"
                                        + reviewId.asString()
                                        + ":"
                                        + clock.millis(),
                                message,
                                claimLease);
        if (sealed instanceof ExecuteTurnResult.Replied replied) {
            return ReviewActionResult.ok(replied.text(), null);
        }
        // 状态已 REJECTED、无 Task；短文落库失败仍回成功，避免用户以为驳回未生效
        return ReviewActionResult.ok(message, null);
    }

    /**
     * 启动/周期对账：CLAIMED 若已有完成确认 Turn+Task → CONFIRMED；否则回 PENDING。
     */
    public void reconcileClaimed() {
        Instant now = clock.instant();
        for (TaskReviewPending row : reviews.listByStatus(TaskReviewStatus.CLAIMED)) {
            try {
                Optional<ReviewActionResult> done = lookupCompletedConfirm(
                        confirmClientRequestId(row.reviewId()));
                if (done.isPresent()) {
                    reviews.casStatus(
                            row.reviewId(),
                            TaskReviewStatus.CLAIMED,
                            TaskReviewStatus.CONFIRMED,
                            now);
                } else {
                    reviews.casStatus(
                            row.reviewId(),
                            TaskReviewStatus.CLAIMED,
                            TaskReviewStatus.PENDING,
                            now);
                }
            } catch (RuntimeException ex) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "reconcileClaimed 失败 review=" + row.reviewId().asString(),
                        ex);
            }
        }
    }

    static String confirmClientRequestId(TaskReviewId reviewId) {
        return "task-review-confirm:" + reviewId.asString();
    }

    private Optional<ReviewActionResult> tryCompleteFromExistingTurn(
            TaskReviewPending pending, Instant now) {
        Optional<ReviewActionResult> done =
                lookupCompletedConfirm(confirmClientRequestId(pending.reviewId()));
        if (done.isEmpty()) {
            return Optional.empty();
        }
        reviews.casStatus(
                pending.reviewId(), TaskReviewStatus.CLAIMED, TaskReviewStatus.CONFIRMED, now);
        return done;
    }

    private Optional<ReviewActionResult> lookupCompletedConfirm(String clientRequestId) {
        String sql =
                """
                SELECT t.id, t.status, bt.id AS task_id
                FROM turn t
                LEFT JOIN background_task bt ON bt.origin_turn_id = t.id
                WHERE t.client_request_id = ?
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientRequestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String status = rs.getString("status");
                if (!"COMPLETED".equals(status)) {
                    return Optional.empty();
                }
                String taskId = rs.getString("task_id");
                return Optional.of(
                        ReviewActionResult.ok("已确认后台任务。", taskId));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("lookupCompletedConfirm 失败", ex);
        }
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
