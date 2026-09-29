package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code background_task} 持久化（2.5.2 形状 + 2.5.4 claim/CAS）。
 */
public interface BackgroundTaskRepository {

    void insertFromDraft(Connection connection, TaskDraft draft, Instant now);

    Optional<TaskDraft> findById(BackgroundTaskId id);

    List<TaskDraft> listByConversation(ConversationId conversationId);

    /** 2.5.9：侧栏/读 API 投影（含 result / 时间列）。 */
    List<BackgroundTaskView> listViewsByConversation(ConversationId conversationId);

    Optional<BackgroundTaskView> findView(BackgroundTaskId id);

    /**
     * 选下一可执行行（{@code CREATED|READY}，排除 {@code SCHEDULED}）并 CAS→{@code RUNNING}。
     * 序：{@code updated_at ASC, id ASC}（F2）。
     */
    Optional<TaskDraft> claimNextExecutable(Connection connection, Instant now);

    /**
     * 2.5.8：到期 {@code SCHEDULED} → {@code CREATED}，写 {@code last_fired_at}；不改 {@code next_fire_at}。
     * 序：{@code next_fire_at ASC, id ASC}。
     */
    Optional<TaskDraft> claimDueScheduled(Connection connection, Instant now);

    /**
     * 2.5.8：多次开火成功后回 {@code SCHEDULED} 并推进 {@code next_fire_at}（清 {@code completed_at}）。
     */
    boolean rescheduleAfterSuccessfulFire(
            Connection connection,
            BackgroundTaskId id,
            Instant nextFireAt,
            Instant now);

    /**
     * 2.5.8：未开火取消 {@code SCHEDULED → CANCELLED}（CAS）。
     */
    boolean cancelScheduled(Connection connection, BackgroundTaskId id, Instant now);

    boolean casStatus(
            Connection connection,
            BackgroundTaskId id,
            BackgroundTaskStatus expected,
            BackgroundTaskStatus next,
            Instant now);

    void writeResult(
            Connection connection,
            BackgroundTaskId id,
            String resultJson,
            BackgroundTaskStatus terminalStatus,
            Instant now);

    /** {@code status=RUNNING} 行数（门闩辅助）。 */
    int countRunning();

    /**
     * 用户从列表删除终态任务：先清 delivery / run 子行，再删 {@code background_task}。
     *
     * @return {@code true} 已删除；{@code false} 不存在或不属于该会话或非终态
     */
    boolean deleteTerminal(ConversationId conversationId, BackgroundTaskId taskId);
}
