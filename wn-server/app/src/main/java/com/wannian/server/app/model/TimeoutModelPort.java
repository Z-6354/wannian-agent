package com.wannian.server.app.model;

import com.wannian.server.app.log.SafeErrorLog;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.error.ErrorLogFields;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按 context.deadline 与固定上限截断内层调用；共享线程池，超时尽量中断内层。 */
public final class TimeoutModelPort implements ModelPort {

    private static final Logger LOG = LoggerFactory.getLogger(TimeoutModelPort.class);

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final ExecutorService SHARED =
            Executors.newCachedThreadPool(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "timeout-model-port-" + THREAD_SEQ.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        }
                    });

    private final ModelPort inner;
    private final Duration hardLimit;

    public TimeoutModelPort(ModelPort inner, Duration hardLimit) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.hardLimit = Objects.requireNonNull(hardLimit, "hardLimit");
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        if (context != null && context.cancelled()) {
            return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
        }
        Duration limit = hardLimit;
        if (context != null && context.deadline() != null) {
            Duration untilDeadline = Duration.between(Instant.now(), context.deadline());
            if (untilDeadline.isNegative() || untilDeadline.isZero()) {
                return new ModelOutcome.Failure(ErrorCodes.MODEL_TIMEOUT, "已超过截止时间", true);
            }
            if (untilDeadline.compareTo(limit) < 0) {
                limit = untilDeadline;
            }
        }
        Instant started = Instant.now();
        String correlationId = context == null ? null : context.traceId();
        Future<ModelOutcome> future = SHARED.submit(() -> inner.decide(request, context));
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            SafeErrorLog.warn(
                    LOG,
                    new ErrorLogFields(
                            ErrorCodes.MODEL_TIMEOUT,
                            "model.decide.timeout",
                            correlationId,
                            Duration.between(started, Instant.now()).toMillis(),
                            "模型调用超时"));
            return new ModelOutcome.Failure(ErrorCodes.MODEL_TIMEOUT, "模型调用超时", true);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            SafeErrorLog.info(
                    LOG,
                    new ErrorLogFields(
                            ErrorCodes.CANCELLED,
                            "model.decide.timeout",
                            correlationId,
                            Duration.between(started, Instant.now()).toMillis(),
                            "调用被中断"));
            return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用被中断", false);
        } catch (ExecutionException ex) {
            future.cancel(true);
            SafeErrorLog.warn(
                    LOG,
                    new ErrorLogFields(
                            ErrorCodes.DEPENDENCY_UNAVAILABLE,
                            "model.decide.timeout",
                            correlationId,
                            Duration.between(started, Instant.now()).toMillis(),
                            "模型调用失败"));
            return new ModelOutcome.Failure(ErrorCodes.DEPENDENCY_UNAVAILABLE, "模型调用失败", true);
        }
    }
}
