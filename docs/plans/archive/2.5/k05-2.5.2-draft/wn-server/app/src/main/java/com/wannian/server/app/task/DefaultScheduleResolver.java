package com.wannian.server.app.task;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.wannian.server.kernel.task.ResolvedSchedule;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.ScheduleSpec;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ScheduleResolver} 默认实现（P2 §5 · D4/D5/D6）。
 *
 * <p>cron：{@code cron-utils} + {@link CronType#UNIX} 5 字段；DOM∩DOW 同时非 {@code *} 时匹配为
 * <strong>OR</strong>（Vixie），本实现不改为 AND。
 * relative offset 仅 {@code Nm}/{@code Nh}/{@code Nd}（如 {@code 20m}/{@code 2d}/{@code 48h}）。
 */
public final class DefaultScheduleResolver implements ScheduleResolver {

    /** D4：everyMs 上限 7 天。 */
    public static final long MAX_EVERY_MS = 7L * 24L * 3600L * 1000L;

    private static final Pattern RELATIVE =
            Pattern.compile("^(\\d+)([mhd])$", Pattern.CASE_INSENSITIVE);

    private final CronParser unixCronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @Override
    public ResolvedSchedule resolve(ScheduleSpec spec, String timezone, Instant now) {
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(now, "now");
        if (timezone.isBlank()) {
            throw new IllegalArgumentException("timezone 不得为空");
        }
        if (spec == null) {
            return ResolvedSchedule.immediate();
        }
        ZoneId zone = zoneId(timezone);
        return switch (spec) {
            case ScheduleSpec.Relative relative -> resolveRelative(relative, now);
            case ScheduleSpec.At at -> resolveAt(at);
            case ScheduleSpec.Every every -> resolveEvery(every, now);
            case ScheduleSpec.Cron cron -> resolveCron(cron, zone, now);
        };
    }

    @Override
    public Optional<Instant> advance(ScheduleSpec spec, String timezone, Instant lastFiredAt) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(lastFiredAt, "lastFiredAt");
        if (timezone.isBlank()) {
            throw new IllegalArgumentException("timezone 不得为空");
        }
        return switch (spec) {
            case ScheduleSpec.Relative ignored -> Optional.empty();
            case ScheduleSpec.At ignored -> Optional.empty();
            case ScheduleSpec.Every every -> {
                validateEveryMs(every.everyMs());
                yield Optional.of(lastFiredAt.plusMillis(every.everyMs()));
            }
            case ScheduleSpec.Cron cron -> {
                ZoneId zone = zoneId(cron.tz().isBlank() ? timezone : cron.tz());
                yield Optional.of(nextCronFire(cron.expr(), zone, lastFiredAt));
            }
        };
    }

    private ResolvedSchedule resolveRelative(ScheduleSpec.Relative relative, Instant now) {
        Duration offset = parseRelativeOffset(relative.offset());
        Instant fireAt = now.plus(offset);
        ScheduleSpec.Relative normalized = new ScheduleSpec.Relative(relative.offset(), fireAt);
        return ResolvedSchedule.scheduled(normalized, fireAt);
    }

    private static ResolvedSchedule resolveAt(ScheduleSpec.At at) {
        return ResolvedSchedule.scheduled(at, at.at());
    }

    private static ResolvedSchedule resolveEvery(ScheduleSpec.Every every, Instant now) {
        validateEveryMs(every.everyMs());
        Instant next = nextEveryFire(every.everyMs(), every.anchorAt(), now);
        return ResolvedSchedule.scheduled(every, next);
    }

    private ResolvedSchedule resolveCron(ScheduleSpec.Cron cron, ZoneId fallbackZone, Instant now) {
        ZoneId zone = zoneId(cron.tz().isBlank() ? fallbackZone.getId() : cron.tz());
        Instant next = nextCronFire(cron.expr(), zone, now);
        ScheduleSpec.Cron normalized = new ScheduleSpec.Cron(cron.expr(), zone.getId());
        return ResolvedSchedule.scheduled(normalized, next);
    }

    static Duration parseRelativeOffset(String offset) {
        Matcher m = RELATIVE.matcher(offset.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "非法 relative offset（仅允许 Nm/Nh/Nd，如 20m/2d/48h）: " + offset);
        }
        long amount = Long.parseLong(m.group(1));
        if (amount <= 0L) {
            throw new IllegalArgumentException("relative offset 须 > 0: " + offset);
        }
        return switch (m.group(2).toLowerCase()) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("非法 relative 单位: " + offset);
        };
    }

    static void validateEveryMs(long everyMs) {
        if (everyMs <= 0L || everyMs > MAX_EVERY_MS) {
            throw new IllegalArgumentException(
                    "everyMs 须 ∈ (0, 7d]，实际=" + everyMs);
        }
    }

    /**
     * 有 anchor 且尚未到期 → 首火=anchor；否则从 anchor（或 now）按步长推到 &gt; now。
     */
    static Instant nextEveryFire(long everyMs, Instant anchorAt, Instant now) {
        Instant base = anchorAt != null ? anchorAt : now;
        if (base.isAfter(now)) {
            return base;
        }
        long elapsed = Duration.between(base, now).toMillis();
        long steps = elapsed / everyMs + 1L;
        return base.plusMillis(steps * everyMs);
    }

    private Instant nextCronFire(String expr, ZoneId zone, Instant after) {
        try {
            Cron cron = unixCronParser.parse(expr.trim());
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime afterZdt = after.atZone(zone);
            return executionTime
                    .nextExecution(afterZdt)
                    .map(ZonedDateTime::toInstant)
                    .orElseThrow(
                            () -> new IllegalArgumentException("cron 无法计算下一火: " + expr));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("非法 cron 表达式: " + expr, ex);
        }
    }

    private static ZoneId zoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("非法 IANA timezone: " + timezone, ex);
        }
    }
}
