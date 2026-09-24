package com.wannian.server.kernel.memory;

import java.time.Duration;
import java.util.Objects;

/**
 * 记忆衰减与弱 B 墓碑判定（纯函数；固定半衰期）。
 *
 * <p>公式：{@code score = w_r × 0.5^(ageDays/HL) + w_i × clamp01(importance)}。
 * 负 age 按 {@link Duration#ZERO} 计分，且不墓碑。
 */
public final class MemoryDecay {

    /** 固定半衰期（天）。 */
    public static final double HALF_LIFE_DAYS = 30.0;

    /** 近因权重。 */
    public static final double WEIGHT_RECENCY = 0.4;

    /** 重要性权重。 */
    public static final double WEIGHT_IMPORTANCE = 0.6;

    /** 弱 B 条款①：score 低于此阈值才可墓碑。 */
    public static final double TOMBSTONE_EPSILON = 0.15;

    /** 弱 B 条款①：最短年龄（天）。 */
    public static final double TOMBSTONE_MIN_AGE_DAYS = 7.0;

    /** 弱 B 条款②：短时低分 importance 上限（开区间外）。 */
    public static final double IMPORTANCE_EPHEMERAL = 0.35;

    /** 弱 B 条款②：短时窗最长年龄（天）。 */
    public static final double EPHEMERAL_MAX_AGE_DAYS = 14.0;

    private static final double NANOS_PER_DAY = 86_400.0 * 1_000_000_000.0;

    private MemoryDecay() {}

    /** {@code 0.5 ^ (ageDays / HALF_LIFE_DAYS)}；负 age 视为 0 天。 */
    public static double decay(Duration age) {
        Objects.requireNonNull(age, "age");
        double ageDays = nonNegativeAgeDays(age);
        return Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    }

    /**
     * 召回分：近因衰减 + 重要性垫底。
     *
     * <p>NaN / Infinite importance 钳制为 0。
     */
    public static double score(double importance, Duration age) {
        Objects.requireNonNull(age, "age");
        double decay = decay(age);
        double clamped = clamp01(importance);
        return WEIGHT_RECENCY * decay + WEIGHT_IMPORTANCE * clamped;
    }

    /**
     * 弱 B（A+B）：满足任一即为墓碑候选。
     *
     * <ol>
     *   <li>{@code ageDays > TOMBSTONE_MIN_AGE_DAYS && score < TOMBSTONE_EPSILON}
     *   <li>{@code clamp01(importance) < IMPORTANCE_EPHEMERAL && ageDays > EPHEMERAL_MAX_AGE_DAYS}
     * </ol>
     *
     * <p>负 age 一律 false。
     */
    public static boolean shouldTombstone(double importance, Duration age) {
        Objects.requireNonNull(age, "age");
        if (age.isNegative()) {
            return false;
        }
        double ageDays = ageDays(age);
        double clamped = clamp01(importance);
        double score = WEIGHT_RECENCY * Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
                + WEIGHT_IMPORTANCE * clamped;
        boolean byScore =
                ageDays > TOMBSTONE_MIN_AGE_DAYS && score < TOMBSTONE_EPSILON;
        boolean byEphemeral =
                clamped < IMPORTANCE_EPHEMERAL && ageDays > EPHEMERAL_MAX_AGE_DAYS;
        return byScore || byEphemeral;
    }

    private static double nonNegativeAgeDays(Duration age) {
        if (age.isNegative()) {
            return 0.0;
        }
        return ageDays(age);
    }

    private static double ageDays(Duration age) {
        return age.toNanos() / NANOS_PER_DAY;
    }

    /** 钳制到 [0,1]；NaN / Infinite → 0。 */
    private static double clamp01(double importance) {
        if (Double.isNaN(importance) || Double.isInfinite(importance)) {
            return 0.0;
        }
        if (importance < 0.0) {
            return 0.0;
        }
        if (importance > 1.0) {
            return 1.0;
        }
        return importance;
    }
}
