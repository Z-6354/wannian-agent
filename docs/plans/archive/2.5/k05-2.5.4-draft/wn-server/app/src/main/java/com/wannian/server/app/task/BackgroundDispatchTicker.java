package com.wannian.server.app.task;

import com.wannian.server.kernel.task.DispatchBudget;
import com.wannian.server.kernel.task.TaskRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 薄调度拍：只调 {@link TaskRuntime#dispatchNext}（2.5.4）。不晋升 SCHEDULED。
 */
public final class BackgroundDispatchTicker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundDispatchTicker.class);

    private final TaskRuntime taskRuntime;

    public BackgroundDispatchTicker(TaskRuntime taskRuntime) {
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
    }

    @Scheduled(fixedDelayString = "${wannian.task.dispatch.tick-ms:5000}")
    public void tick() {
        try {
            taskRuntime.dispatchNext(DispatchBudget.DEFAULT);
        } catch (RuntimeException ex) {
            log.warn("BackgroundDispatchTicker 单拍失败: {}", ex.toString());
        }
    }
}
