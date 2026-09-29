package com.wannian.server.api.common;

import java.util.Objects;
import java.util.UUID;

/**
 * 后台 Task 稳定身份，对应表 {@code background_task.id}。
 *
 * <p>应用层生成 UUID，写入前即具备身份；禁止用数据库自增代替。
 * 与 {@link TurnId}、{@link ConversationId}、{@link SubAgentRunId} 分类型，避免误传。
 *
 * <p>换 MySQL 时本类型通常不变，仅 Adapter/列类型可能调整。
 */
public record BackgroundTaskId(UUID value) {

    /**
     * @param value 非 null 的 UUID
     * @throws NullPointerException 当 value 为 null
     */
    public BackgroundTaskId {
        Objects.requireNonNull(value, "BackgroundTaskId.value");
    }

    /** 生成新的后台 Task 身份。 */
    public static BackgroundTaskId generate() {
        return new BackgroundTaskId(UUID.randomUUID());
    }

    /** 从规范 UUID 字符串解析。 */
    public static BackgroundTaskId parse(String text) {
        Objects.requireNonNull(text, "text");
        return new BackgroundTaskId(UUID.fromString(text.trim()));
    }

    /** 供持久化 / 日志使用的规范字符串（UUID 标准形式）。 */
    public String asString() {
        return value.toString();
    }
}
