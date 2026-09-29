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

    List<TaskDeliveryPending> listQueued(ConversationId conversationId);

    boolean casDelivered(TaskDeliveryId id, Instant deliveredAt);
}
