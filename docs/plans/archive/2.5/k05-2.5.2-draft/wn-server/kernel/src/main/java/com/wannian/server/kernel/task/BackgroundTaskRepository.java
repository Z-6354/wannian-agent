package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code background_task} 持久化（P2 §8）。
 *
 * <p>本号可实现 INSERT/find；<strong>不被</strong> Committer 调用（接线在 2.5.3）。
 * {@code Connection} 入参供同事务插入（与 Turn 提交共用连接）。
 */
public interface BackgroundTaskRepository {

    /**
     * 按 Draft 插入一行（{@code initialStatus} 为 {@code CREATED} 或 {@code SCHEDULED}）。
     *
     * <p>方法名用 {@code insertFromDraft}（非 {@code insertCreated}），避免 SCHEDULED 语义被误导。
     *
     * @param connection 非空；调用方事务连接
     * @param draft 非空
     * @param now 写入 created_at/updated_at
     */
    void insertFromDraft(Connection connection, TaskDraft draft, Instant now);

    /**
     * 按 id 加载。
     *
     * <p>本号用 {@link TaskDraft} 作行快照：读回时 {@code initialStatus} 表示<strong>当前</strong>
     * {@code status}（非仅插入建议态）。后号可拆独立实体。
     */
    Optional<TaskDraft> findById(BackgroundTaskId id);

    /**
     * 会话侧栏预埋（HTTP 属 2.5.9）；本号可实现或空列表。
     *
     * @param conversationId 非空
     */
    List<TaskDraft> listByConversation(ConversationId conversationId);
}
