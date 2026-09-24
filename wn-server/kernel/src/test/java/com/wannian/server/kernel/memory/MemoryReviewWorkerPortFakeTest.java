package com.wannian.server.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Worker Port 可替换：假实现可注入、可断言。 */
class MemoryReviewWorkerPortFakeTest {

    @Test
    void fakeWorkerPortIsReplaceable() {
        AtomicInteger polls = new AtomicInteger();
        MemoryReviewWorkerPort fake =
                () -> {
                    polls.incrementAndGet();
                    return polls.get() == 1;
                };

        assertThat(fake.pollOnce()).isTrue();
        assertThat(fake.pollOnce()).isFalse();
        assertThat(polls.get()).isEqualTo(2);
    }
}
