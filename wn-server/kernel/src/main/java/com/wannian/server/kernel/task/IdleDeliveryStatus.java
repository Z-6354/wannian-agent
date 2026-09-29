package com.wannian.server.kernel.task;

/** Idle 待唤醒队列状态（2.5.7）。 */
public enum IdleDeliveryStatus {
    QUEUED,
    DISPATCHING,
    DELIVERED,
    CANCELLED
}
