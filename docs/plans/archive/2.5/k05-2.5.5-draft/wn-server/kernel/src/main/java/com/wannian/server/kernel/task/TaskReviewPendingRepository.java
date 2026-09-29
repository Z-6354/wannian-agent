package com.wannian.server.kernel.task;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户待审后台提案持久化（2.5.5 · V025）。
 */
public interface TaskReviewPendingRepository {

    void insert(TaskReviewPending pending);

    Optional<TaskReviewPending> findById(TaskReviewId id);

    List<TaskReviewPending> listByConversation(ConversationId conversationId, TaskReviewStatus status);

    /**
     * 状态 CAS；成功返回 true。
     *
     * @param expected 期望当前状态
     * @param next 目标状态
     * @param now 写入 updated 语义用（本表无 updated 列时可忽略）
     */
    boolean casStatus(
            TaskReviewId id, TaskReviewStatus expected, TaskReviewStatus next, Instant now);
}
