package com.wannian.server.kernel.task;

import java.time.Instant;
import java.util.Objects;

/**
 * 定时规则判别联合（Q10）：{@code relative} | {@code at} | {@code every} | {@code cron}。
 *
 * <p>立即执行用 {@code null}（不放本联合内）。禁止 RRULE 主存；禁止与 every 歧义的裸时长串。
 * Cron 的 DOM∩DOW 语义为 <strong>OR</strong>（Vixie），实现不得静默改成 AND。
 */
public sealed interface ScheduleSpec
        permits ScheduleSpec.Relative, ScheduleSpec.At, ScheduleSpec.Every, ScheduleSpec.Cron {

    /** 是否多次（every/cron）；relative/at 为单次。 */
    boolean recurring();

    /**
     * 相对延迟单次（如 {@code 20m}/{@code 2d}/{@code 48h}）。
     *
     * @param offset 非空相对串
     * @param resolvedAt Resolver 填入的绝对点；prepare 成功后非空
     */
    record Relative(String offset, Instant resolvedAt) implements ScheduleSpec {
        public Relative {
            Objects.requireNonNull(offset, "offset");
            if (offset.isBlank()) {
                throw new IllegalArgumentException("offset 不得为空");
            }
        }

        @Override
        public boolean recurring() {
            return false;
        }
    }

    /**
     * 绝对时刻单次；{@code at} 须为带 offset 的瞬时（禁裸本地无区）。
     *
     * @param at 触发时刻
     */
    record At(Instant at) implements ScheduleSpec {
        public At {
            Objects.requireNonNull(at, "at");
        }

        @Override
        public boolean recurring() {
            return false;
        }
    }

    /**
     * 固定间隔多次。
     *
     * @param everyMs 间隔毫秒，(0, 7d] 由 Resolver/prepare 校验（D4）
     * @param anchorAt 可选对齐锚点
     */
    record Every(long everyMs, Instant anchorAt) implements ScheduleSpec {
        public Every {
            if (everyMs <= 0L) {
                throw new IllegalArgumentException("everyMs 须 > 0");
            }
        }

        @Override
        public boolean recurring() {
            return true;
        }
    }

    /**
     * 5 字段 Unix cron（分 时 日 月 周）+ IANA tz。
     *
     * <p>DOM 与 DOW 同时非 {@code *} 时匹配语义为 OR，不是 AND。
     *
     * @param expr 非空 cron 表达式
     * @param tz 非空 IANA，如 {@code Asia/Shanghai}
     */
    record Cron(String expr, String tz) implements ScheduleSpec {
        public Cron {
            Objects.requireNonNull(expr, "expr");
            Objects.requireNonNull(tz, "tz");
            if (expr.isBlank()) {
                throw new IllegalArgumentException("expr 不得为空");
            }
            if (tz.isBlank()) {
                throw new IllegalArgumentException("tz 不得为空");
            }
        }

        @Override
        public boolean recurring() {
            return true;
        }
    }
}
