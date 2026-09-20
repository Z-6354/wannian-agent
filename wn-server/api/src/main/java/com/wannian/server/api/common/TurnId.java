package com.wannian.server.api.common;

import java.util.Objects;
import java.util.UUID;

/**
 * 回合稳定身份，对应表 {@code turn.id}。
 *
 * <p>应用层生成 UUID，写入前即具备身份；禁止用数据库自增代替。
 * 与 {@link ConversationId}、{@link MessageId} 分类型，避免误传。
 *
 * <p>换 MySQL 时本类型通常不变，仅 Adapter/列类型可能调整。
 */
public record TurnId(UUID value) {

    /**
     * @param value 非 null 的 UUID
     * @throws NullPointerException 当 value 为 null
     */
    public TurnId {
        Objects.requireNonNull(value, "TurnId.value");
    }

    /** 生成新的回合身份。 */
    public static TurnId generate() {
        return new TurnId(UUID.randomUUID());
    }

    /** 供持久化 / 日志使用的规范字符串（UUID 标准形式）。 */
    public String asString() {
        return value.toString();
    }
}
