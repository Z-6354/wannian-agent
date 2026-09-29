package com.wannian.server.app.task;

import com.wannian.server.kernel.task.TaskRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 2.5.8：到期 {@code SCHEDULED → CREATED} 晋升拍。不跑 Executor；不塞进 {@link BackgroundDispatchTicker}。
 */
public final class SchedulePromoteTicker {

    private static final Logger log = LoggerFactory.getLogger(SchedulePromoteTicker.class);

    private final TaskRuntime taskRuntime;
    private final int promoteBatch;

    public SchedulePromoteTicker(
            TaskRuntime taskRuntime,
            @Value("${wannian.task.schedule.promote-batch:4}") int promoteBatch) {
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.promoteBatch = Math.max(1, promoteBatch);
    }

    @Scheduled(fixedDelayString = "${wannian.task.schedule.promote-tick-ms:5000}")
    public void tick() {
        try {
            int n = taskRuntime.promoteDueScheduled(promoteBatch);
            if (n > 0) {
                log.info("SchedulePromoteTicker 晋升 {} 条", n);
            }
        } catch (RuntimeException ex) {
            if (isBenignDbRace(ex)) {
                log.debug("SchedulePromoteTicker 忙等: {}", ex.toString());
            } else {
                log.warn("SchedulePromoteTicker 单拍失败: {}", ex.toString());
            }
        }
    }

    private static boolean isBenignDbRace(RuntimeException ex) {
        String s = ex.toString().toLowerCase();
        return s.contains("busy")
                || s.contains("locked")
                || s.contains("no such table")
                || s.contains("closed");
    }
}
