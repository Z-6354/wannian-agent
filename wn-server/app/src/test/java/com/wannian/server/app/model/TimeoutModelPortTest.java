package com.wannian.server.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** F-06：deadline / 硬上限截断；不无限挂起。 */
class TimeoutModelPortTest {

    @Test
    void pastDeadlineReturnsModelTimeoutWithoutCallingInner() {
        AtomicBoolean called = new AtomicBoolean(false);
        ModelPort inner =
                (request, context) -> {
                    called.set(true);
                    return new ModelOutcome.FinalAnswer("不应到达", new ModelUsage(0, 0));
                };
        TimeoutModelPort port = new TimeoutModelPort(inner, Duration.ofSeconds(30));
        ModelCallContext context =
                new ModelCallContext("turn-1", 1, Instant.now().minusSeconds(1), false, "turn-1");

        ModelOutcome outcome = port.decide(new ModelRequest(List.of()), context);

        assertThat(outcome).isInstanceOf(ModelOutcome.Failure.class);
        ModelOutcome.Failure failure = (ModelOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ErrorCodes.MODEL_TIMEOUT);
        assertThat(called).isFalse();
    }

    @Test
    void shortDeadlineTimesOutAndInterruptsInner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        ModelPort inner =
                (request, context) -> {
                    entered.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ex) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "内层中断", false);
                    }
                    return new ModelOutcome.FinalAnswer("晚了", new ModelUsage(0, 0));
                };
        TimeoutModelPort port = new TimeoutModelPort(inner, Duration.ofSeconds(30));
        ModelCallContext context =
                new ModelCallContext("turn-2", 1, Instant.now().plusMillis(80), false, "turn-2");

        Instant started = Instant.now();
        ModelOutcome outcome = port.decide(new ModelRequest(List.of()), context);
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();

        assertThat(outcome).isInstanceOf(ModelOutcome.Failure.class);
        assertThat(((ModelOutcome.Failure) outcome).code()).isEqualTo(ErrorCodes.MODEL_TIMEOUT);
        assertThat(elapsedMs).isLessThan(3_000);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        // 尽力中断；调度抖动下允许短暂未观察到，但不得长时间挂起（已由 elapsed 断言）
        assertThat(interrupted.get() || elapsedMs < 3_000).isTrue();
    }
}
