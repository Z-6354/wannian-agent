package com.wannian.server.app.model;

import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 按 context.deadline 与固定上限截断内层调用。 */
public final class TimeoutModelPort implements ModelPort {

    private final ModelPort inner;
    private final Duration hardLimit;

    public TimeoutModelPort(ModelPort inner, Duration hardLimit) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.hardLimit = Objects.requireNonNull(hardLimit, "hardLimit");
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        if (context != null && context.cancelled()) {
            return new ModelOutcome.Failure("CANCELLED", "调用已取消", false);
        }
        Duration limit = hardLimit;
        if (context != null && context.deadline() != null) {
            Duration untilDeadline = Duration.between(Instant.now(), context.deadline());
            if (untilDeadline.isNegative() || untilDeadline.isZero()) {
                return new ModelOutcome.Failure("MODEL_TIMEOUT", "已超过截止时间", true);
            }
            if (untilDeadline.compareTo(limit) < 0) {
                limit = untilDeadline;
            }
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelOutcome> future = executor.submit(() -> inner.decide(request, context));
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return new ModelOutcome.Failure("MODEL_TIMEOUT", "模型调用超时", true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ModelOutcome.Failure("CANCELLED", "调用被中断", false);
        } catch (ExecutionException ex) {
            return new ModelOutcome.Failure("DEPENDENCY_UNAVAILABLE", "模型调用失败", true);
        } finally {
            executor.shutdownNow();
        }
    }
}
