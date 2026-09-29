package com.wannian.server.kernel.task;

import java.time.Instant;
import java.util.Objects;

/**
 * {@link ScheduleResolver#resolve} 产物：规范化后的 spec + 首个 {@code nextFireAt}。
 *
 * <p>立即：两字段皆 {@code null}。定时：两字段皆非空（spec 已 resolve，如 Relative 带 {@code resolvedAt}）。
 * 禁止「半空」组合。本类型不写库。
 */
public record ResolvedSchedule(ScheduleSpec scheduleSpec, Instant nextFireAt) {

    /**
     * @param scheduleSpec 立即为 null；定时非空且已规范化
     * @param nextFireAt 立即为 null；定时非空
     * @throws IllegalArgumentException 一空一非空
     */
    public ResolvedSchedule {
        if ((scheduleSpec == null) != (nextFireAt == null)) {
            throw new IllegalArgumentException(
                    "ResolvedSchedule：立即须双 null，定时须双非空");
        }
    }

    /** 立即执行（无 fire）。 */
    public static ResolvedSchedule immediate() {
        return new ResolvedSchedule(null, null);
    }

    /**
     * 定时：规范化 spec + 首火。
     *
     * @param scheduleSpec 非空
     * @param nextFireAt 非空
     */
    public static ResolvedSchedule scheduled(ScheduleSpec scheduleSpec, Instant nextFireAt) {
        Objects.requireNonNull(scheduleSpec, "scheduleSpec");
        Objects.requireNonNull(nextFireAt, "nextFireAt");
        return new ResolvedSchedule(scheduleSpec, nextFireAt);
    }

    /** 是否立即（无下次触发）。 */
    public boolean isImmediate() {
        return scheduleSpec == null;
    }
}
