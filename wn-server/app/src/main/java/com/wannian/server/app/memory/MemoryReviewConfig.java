package com.wannian.server.app.memory;

import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.memory.MemoryCommand;
import com.wannian.server.kernel.memory.MemoryReviewBatchApplier;
import com.wannian.server.kernel.memory.MemoryReviewLlm;
import com.wannian.server.kernel.memory.MemoryReviewWorkerPort;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.MemoryTombstoneScanner;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Review 调度 / Worker / 空闲扫 / 弱 B 扫墓装配。
 *
 * <p><b>tick 顺序（实施单写死）</b>：{@code idle.scanOnce} → {@code worker.pollOnce}×N →
 * {@code tombstone.scanOnce}（同 tick <strong>末尾</strong>）。弱 B 零 LLM，不进 review_job 表。
 *
 * <p>{@link MemoryTombstoneScanner} bean：{@code wannian.memory.tombstone.batch-limit}。
 */
@Configuration
@EnableScheduling
public class MemoryReviewConfig {

    @Bean
    @ConditionalOnMissingBean(MemoryReviewLlm.class)
    MemoryReviewLlm memoryReviewLlm() {
        return request -> List.of();
    }

    @Bean
    MemoryReviewWorkerPort memoryReviewWorkerPort(
            DataSource dataSource,
            ConversationStore conversations,
            MemoryStore memoryStore,
            MemoryReviewLlm reviewLlm,
            MemoryReviewBatchApplier batchApplier) {
        return new InProcessMemoryReviewWorker(
                dataSource, conversations, memoryStore, reviewLlm, batchApplier);
    }

    @Bean
    MemoryTombstoneScanner memoryTombstoneScanner(
            MemoryStore memoryStore,
            MemoryCommand memoryCommand,
            @Value("${wannian.memory.tombstone.batch-limit:50}") int batchLimit) {
        return new SqliteDefaultTombstoneScanner(memoryStore, memoryCommand, batchLimit);
    }

    @Bean
    @ConditionalOnProperty(
            name = "wannian.memory.review.scheduler-enabled",
            havingValue = "true",
            matchIfMissing = true)
    MemoryReviewTicker memoryReviewTicker(
            MemoryIdleScanner idleScanner,
            MemoryReviewWorkerPort worker,
            MemoryTombstoneScanner tombstoneScanner) {
        return new MemoryReviewTicker(idleScanner, worker, tombstoneScanner);
    }

    static final class MemoryReviewTicker {
        private static final int MAX_POLLS_PER_TICK = 3;

        private final MemoryIdleScanner idleScanner;
        private final MemoryReviewWorkerPort worker;
        private final MemoryTombstoneScanner tombstoneScanner;

        MemoryReviewTicker(
                MemoryIdleScanner idleScanner,
                MemoryReviewWorkerPort worker,
                MemoryTombstoneScanner tombstoneScanner) {
            this.idleScanner = idleScanner;
            this.worker = worker;
            this.tombstoneScanner = tombstoneScanner;
        }

        @Scheduled(fixedDelayString = "${wannian.memory.review.tick-ms:60000}")
        void tick() {
            idleScanner.scanOnce(Instant.now());
            for (int i = 0; i < MAX_POLLS_PER_TICK; i++) {
                if (!worker.pollOnce()) {
                    break;
                }
            }
            tombstoneScanner.scanOnce();
        }
    }
}
