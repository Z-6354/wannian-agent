package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskDeliveryId;
import java.time.Instant;
import java.util.Objects;

/**
 * Busy 待交付快照（2.5.6）。确认前不得冒充已向用户展示。
 */
public record TaskDeliveryPending(
        TaskDeliveryId deliveryId,
        ConversationId conversationId,
        BackgroundTaskId taskId,
        BackgroundTaskStatus terminalStatus,
        String payloadJson,
        TaskDeliveryStatus status,
        Instant createdAt,
        Instant deliveredAt) {

    public TaskDeliveryPending {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
