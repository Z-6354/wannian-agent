package com.wannian.server.kernel.task;

import java.time.Instant;
import java.util.Optional;

/**
 * 定时规则解析：物化 {@code nextFireAt}、规范化 {@link ScheduleSpec}（P2 §5）。
 *
 * <p>无写库副作用。非法 offset / cron / tz / everyMs → {@link IllegalArgumentException}（D1）。
 * 默认实现在 app（cron-utils）；本接口留在 kernel。
 */
public interface ScheduleResolver {

    /**
     * prepare 用：立即或定时 → {@link ResolvedSchedule}。
     *
     * @param spec {@code null}=立即；非空=定时规则
     * @param timezone IANA，非空（D5 默认 {@code Asia/Shanghai} 由调用方填入）
     * @param now 时钟
     * @return 立即双 null；定时为规范化 spec + 首个 nextFireAt
     * @throws IllegalArgumentException 非法 DSL / tz
     */
    ResolvedSchedule resolve(ScheduleSpec spec, String timezone, Instant now);

    /**
     * 开火后推进下一火（供 2.5.8 Ticker；本号可真实现）。
     *
     * @param spec 非空已存规则
     * @param timezone IANA
     * @param lastFiredAt 本次开火时刻
     * @return 单次（relative/at）→ empty；every/cron → 下一 nextFireAt
     * @throws IllegalArgumentException 非法 spec / tz
     */
    Optional<Instant> advance(ScheduleSpec spec, String timezone, Instant lastFiredAt);
}
