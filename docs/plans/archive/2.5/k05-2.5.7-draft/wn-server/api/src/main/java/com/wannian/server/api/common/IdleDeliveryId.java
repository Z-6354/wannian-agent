package com.wannian.server.api.common;

import java.util.Objects;
import java.util.UUID;

/** Idle 唤模交付单身份，对应表 {@code idle_delivery_pending.id}。 */
public record IdleDeliveryId(UUID value) {

    public IdleDeliveryId {
        Objects.requireNonNull(value, "IdleDeliveryId.value");
    }

    public static IdleDeliveryId generate() {
        return new IdleDeliveryId(UUID.randomUUID());
    }

    public String asString() {
        return value.toString();
    }

    public static IdleDeliveryId parse(String text) {
        Objects.requireNonNull(text, "text");
        return new IdleDeliveryId(UUID.fromString(text.trim()));
    }
}
