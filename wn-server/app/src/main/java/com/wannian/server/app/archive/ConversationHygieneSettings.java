package com.wannian.server.app.archive;

import java.time.DayOfWeek;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 会话卫生 / 自动归档配置（0.2.4-G）。 */
@Component
public class ConversationHygieneSettings {

    public enum ScheduleKind {
        DAILY,
        WEEKLY
    }

    public enum EvaluatorKind {
        LLM,
        HEURISTIC
    }

    private final int idleArchiveDays;
    private final ScheduleKind scheduleKind;
    private final DayOfWeek archiveWeekday;
    private final int archiveAtHour;
    private final int archiveAtMinute;
    private final int archiveBatchLimit;
    private final EvaluatorKind evaluatorKind;

    public ConversationHygieneSettings(
            @Value("${wannian.conversation.idle-archive-days:7}") int idleArchiveDays,
            @Value("${wannian.conversation.archive-schedule:DAILY}") String schedule,
            @Value("${wannian.conversation.archive-weekday:1}") int weekday,
            @Value("${wannian.conversation.archive-at-hour:0}") int hour,
            @Value("${wannian.conversation.archive-at-minute:0}") int minute,
            @Value("${wannian.conversation.archive-batch-limit:20}") int batchLimit,
            @Value("${wannian.conversation.archive-evaluator:llm}") String evaluator) {
        this.idleArchiveDays = Math.max(1, idleArchiveDays);
        this.scheduleKind = parseSchedule(schedule);
        int w = ((weekday - 1) % 7 + 7) % 7 + 1;
        this.archiveWeekday = DayOfWeek.of(w);
        this.archiveAtHour = Math.min(23, Math.max(0, hour));
        this.archiveAtMinute = Math.min(59, Math.max(0, minute));
        this.archiveBatchLimit = Math.min(50, Math.max(1, batchLimit));
        this.evaluatorKind = parseEvaluator(evaluator);
    }

    public int idleArchiveDays() {
        return idleArchiveDays;
    }

    public ScheduleKind scheduleKind() {
        return scheduleKind;
    }

    public DayOfWeek archiveWeekday() {
        return archiveWeekday;
    }

    public int archiveAtHour() {
        return archiveAtHour;
    }

    public int archiveAtMinute() {
        return archiveAtMinute;
    }

    public int archiveBatchLimit() {
        return archiveBatchLimit;
    }

    public EvaluatorKind evaluatorKind() {
        return evaluatorKind;
    }

    private static ScheduleKind parseSchedule(String raw) {
        String s = raw == null ? "DAILY" : raw.trim().toUpperCase(Locale.ROOT);
        if ("WEEKLY".equals(s)) {
            return ScheduleKind.WEEKLY;
        }
        return ScheduleKind.DAILY;
    }

    private static EvaluatorKind parseEvaluator(String raw) {
        String s = raw == null ? "LLM" : raw.trim().toUpperCase(Locale.ROOT);
        if ("HEURISTIC".equals(s)) {
            return EvaluatorKind.HEURISTIC;
        }
        return EvaluatorKind.LLM;
    }
}
