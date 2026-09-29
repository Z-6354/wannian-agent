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
}
