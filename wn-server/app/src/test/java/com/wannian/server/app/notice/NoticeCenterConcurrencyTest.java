package com.wannian.server.app.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.kernel.notice.NoticeKind;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S5：发布/撤销复合操作在锁下原子，同 task+kind+终态最多一条。 */
class NoticeCenterConcurrencyTest {

    @Test
    void concurrentPublishSameTaskKeepsSingleOpenNotice() throws Exception {
        NoticeCenter center = new NoticeCenter(32);
        ConversationId conversationId = ConversationId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        start.await();
                                        center.publishTaskTerminal(
                                                conversationId,
                                                taskId,
                                                BackgroundTaskStatus.SUCCEEDED,
                                                null,
                                                "done-" + Thread.currentThread().threadId(),
                                                false);
                                    } catch (Exception ex) {
                                        errors.incrementAndGet();
                                    }
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        long openForTask =
                center.listOpen().stream()
                        .filter(n -> taskId.asString().equals(n.taskId()))
                        .filter(n -> n.kind() == NoticeKind.TASK_COMPLETED)
                        .count();
        assertThat(openForTask).isEqualTo(1);
    }

    @Test
    void concurrentPublishAndDismissDoesNotThrow() throws Exception {
        NoticeCenter center = new NoticeCenter(32);
        ConversationId conversationId = ConversationId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        center.publishTaskTerminal(
                conversationId, taskId, BackgroundTaskStatus.FAILED, "X", "fail", false);
        String noticeId = center.listOpen().get(0).noticeId();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    center.publishTaskTerminal(
                                            conversationId,
                                            taskId,
                                            BackgroundTaskStatus.FAILED,
                                            "X",
                                            "retry",
                                            false);
                                    center.dismiss(noticeId);
                                    center.dismissAllOpen();
                                    center.listOpen();
                                    return null;
                                }));
            }
            start.countDown();
            assertThatCode(
                            () -> {
                                for (Future<?> f : futures) {
                                    f.get(5, TimeUnit.SECONDS);
                                }
                            })
                    .doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }
    }
}
