package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.IdleDeliveryId;
import com.wannian.server.api.common.TurnId;
import java.time.Instant;
import java.util.Objects;

/**
 * Idle 待唤醒快照（2.5.7）。确认展示前不得冒充已向用户汇报。
 */
public record IdleDeliveryPending(
        IdleDeliveryId deliveryId,
        ConversationId conversationId,
        BackgroundTaskId taskId,
        BackgroundTaskStatus terminalStatus,
        String payloadJson,
        IdleDeliveryStatus status,
        Instant createdAt,
        Instant deliveredAt,
        TurnId wakeTurnId) {

    public IdleDeliveryPending {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
