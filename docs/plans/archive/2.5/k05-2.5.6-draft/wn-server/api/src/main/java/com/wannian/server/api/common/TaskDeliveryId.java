package com.wannian.server.api.common;

import java.util.Objects;
import java.util.UUID;

/** 后台任务完成交付单身份，对应表 {@code task_delivery_pending.id}。 */
public record TaskDeliveryId(UUID value) {

    public TaskDeliveryId {
        Objects.requireNonNull(value, "TaskDeliveryId.value");
    }

    public static TaskDeliveryId generate() {
        return new TaskDeliveryId(UUID.randomUUID());
    }

    public String asString() {
        return value.toString();
    }

    public static TaskDeliveryId parse(String text) {
        Objects.requireNonNull(text, "text");
        return new TaskDeliveryId(UUID.fromString(text.trim()));
    }
}
