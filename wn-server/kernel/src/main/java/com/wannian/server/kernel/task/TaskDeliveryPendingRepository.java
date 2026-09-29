package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskDeliveryId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Busy 待交付队列（2.5.6 · V026）。 */
public interface TaskDeliveryPendingRepository {

    void insert(TaskDeliveryPending pending);

    Optional<TaskDeliveryPending> findByTaskTerminal(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus);

    /** 2.5.8：仅 QUEUED（多次开火幂等：已 DELIVERED 不挡下一火）。 */
    Optional<TaskDeliveryPending> findActiveQueued(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus);

    /** 2.5.8.2：QUEUED 且 payload.runId 匹配。 */
    Optional<TaskDeliveryPending> findActiveQueuedForRun(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus, String runId);

    List<TaskDeliveryPending> listQueued(ConversationId conversationId);

    boolean casDelivered(TaskDeliveryId id, Instant deliveredAt);

    /** 会话不可交付等：QUEUED → CANCELLED。 */
    boolean casCancelled(TaskDeliveryId id);
}
