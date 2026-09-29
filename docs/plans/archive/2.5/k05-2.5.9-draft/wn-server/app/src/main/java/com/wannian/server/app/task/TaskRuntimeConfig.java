package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.persistence.SqliteBackgroundTaskRepository;
import com.wannian.server.app.persistence.SqliteIdleDeliveryPendingRepository;
import com.wannian.server.app.persistence.SqliteSubAgentRunRepository;
import com.wannian.server.app.persistence.SqliteTaskDeliveryPendingRepository;
import com.wannian.server.app.persistence.SqliteTaskReviewPendingRepository;
import com.wannian.server.app.persistence.SqliteTurnQueue;
import com.wannian.server.app.stream.DurableTurnScheduler;
import com.wannian.server.kernel.task.BackgroundConcurrencyGate;
import com.wannian.server.kernel.task.BackgroundPolicy;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.IdleDeliveryPendingRepository;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.TaskDeliveryPendingRepository;
import com.wannian.server.kernel.task.TaskExecutor;
import com.wannian.server.kernel.task.TaskReviewPendingRepository;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Task 模块装配（2.5.2–2.5.7：prepare / 执行 / Policy审核 / Busy·Idle 交付）。
 *
 * <p>F13 已钉：构造 {@link DefaultTaskRuntime} 时校验 {@code lease ≥ deadline + 30s}。
 */
@Configuration
public class TaskRuntimeConfig {

    @Bean
    ScheduleResolver scheduleResolver() {
        return new DefaultScheduleResolver();
    }

    @Bean
    BackgroundTaskRepository backgroundTaskRepository(DataSource dataSource) {
        return new SqliteBackgroundTaskRepository(dataSource);
    }

    @Bean
    SubAgentRunRepository subAgentRunRepository(DataSource dataSource) {
        return new SqliteSubAgentRunRepository(dataSource);
    }

    @Bean
    BackgroundConcurrencyGate backgroundConcurrencyGate(
            SubAgentRunRepository subAgentRunRepository, DataSource dataSource) {
        return new DefaultBackgroundConcurrencyGate(
                subAgentRunRepository, dataSource, Clock.systemUTC());
    }

    @Bean
    TaskExecutor taskExecutor(
            ToolRuntime toolRuntime,
            ObjectMapper objectMapper,
            @Value("${wannian.task.executor.deadline:4m}") Duration deadline,
            @Value("${wannian.task.result.max-chars:32768}") int maxResultChars) {
        return new LocalTaskExecutor(
                toolRuntime, objectMapper, Clock.systemUTC(), deadline, maxResultChars);
    }

    @Bean
    SqliteTaskDeliveryPendingRepository sqliteTaskDeliveryPendingRepository(DataSource dataSource) {
        return new SqliteTaskDeliveryPendingRepository(dataSource);
    }

    @Bean
    TaskDeliveryPendingRepository taskDeliveryPendingRepository(
            SqliteTaskDeliveryPendingRepository sqliteTaskDeliveryPendingRepository) {
        return sqliteTaskDeliveryPendingRepository;
    }

    @Bean
    SqliteIdleDeliveryPendingRepository sqliteIdleDeliveryPendingRepository(DataSource dataSource) {
        return new SqliteIdleDeliveryPendingRepository(dataSource);
    }

    @Bean
    IdleDeliveryPendingRepository idleDeliveryPendingRepository(
            SqliteIdleDeliveryPendingRepository sqliteIdleDeliveryPendingRepository) {
        return sqliteIdleDeliveryPendingRepository;
    }

    @Bean
    IdleDeliveryWorker idleDeliveryWorker(
            IdleDeliveryPendingRepository idleDeliveryPendingRepository,
            SqliteTurnQueue turnQueue,
            TurnCommitter turnCommitter,
            @Lazy DurableTurnScheduler durableTurnScheduler,
            ObjectMapper objectMapper,
            @Value("${wannian.task.idle-delivery.drain-batch:2}") int drainBatch) {
        return new IdleDeliveryWorker(
                idleDeliveryPendingRepository,
                turnQueue::conversationBusyForDelivery,
                turnCommitter,
                durableTurnScheduler,
                Clock.systemUTC(),
                objectMapper,
                drainBatch);
    }

    @Bean
    TaskDeliveryService taskDeliveryService(
            SqliteTaskDeliveryPendingRepository sqliteTaskDeliveryPendingRepository,
            IdleDeliveryPendingRepository idleDeliveryPendingRepository,
            DataSource dataSource,
            ObjectMapper objectMapper,
            SqliteTurnQueue turnQueue,
            @Lazy IdleDeliveryWorker idleDeliveryWorker,
            @Value("${wannian.task.delivery.preview-chars:2048}") int previewChars) {
        return new TaskDeliveryService(
                sqliteTaskDeliveryPendingRepository,
                idleDeliveryPendingRepository,
                dataSource,
                objectMapper,
                Clock.systemUTC(),
                previewChars,
                turnQueue::conversationBusyForDelivery,
                idleDeliveryWorker::nudge);
    }

    @Bean
    TaskRuntime taskRuntime(
            ScheduleResolver scheduleResolver,
            DataSource dataSource,
            BackgroundTaskRepository backgroundTaskRepository,
            SubAgentRunRepository subAgentRunRepository,
            TaskExecutor taskExecutor,
            BackgroundConcurrencyGate concurrencyGate,
            ObjectMapper objectMapper,
            @Lazy TaskDeliveryService taskDeliveryService,
            @Value("${wannian.task.run.lease-duration:5m}") Duration leaseDuration,
            @Value("${wannian.task.executor.deadline:4m}") Duration executorDeadline,
            @Value("${wannian.task.retry.max-attempts:2}") int maxAttempts) {
        return new DefaultTaskRuntime(
                scheduleResolver,
                Clock.systemUTC(),
                dataSource,
                backgroundTaskRepository,
                subAgentRunRepository,
                taskExecutor,
                concurrencyGate,
                leaseDuration,
                executorDeadline,
                maxAttempts,
                objectMapper,
                taskDeliveryService);
    }

    @Bean
    BackgroundTaskQueryService backgroundTaskQueryService(
            BackgroundTaskRepository backgroundTaskRepository,
            @Value("${wannian.task.delivery.preview-chars:2048}") int previewChars) {
        return new BackgroundTaskQueryService(backgroundTaskRepository, previewChars);
    }

    @Bean
    BackgroundDispatchTicker backgroundDispatchTicker(TaskRuntime taskRuntime) {
        return new BackgroundDispatchTicker(taskRuntime);
    }

    @Bean
    SchedulePromoteTicker schedulePromoteTicker(
            TaskRuntime taskRuntime,
            @Value("${wannian.task.schedule.promote-batch:4}") int promoteBatch) {
        return new SchedulePromoteTicker(taskRuntime, promoteBatch);
    }

    @Bean
    BackgroundPolicy backgroundPolicy() {
        return new DefaultBackgroundPolicy();
    }

    @Bean
    TaskReviewPendingRepository taskReviewPendingRepository(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new SqliteTaskReviewPendingRepository(dataSource, objectMapper);
    }

    @Bean
    TaskReviewService taskReviewService(
            TaskReviewPendingRepository taskReviewPendingRepository,
            TaskRuntime taskRuntime,
            @Lazy TurnEngine turnEngine,
            @Value("${wannian.task.review.claim-lease:60s}") Duration claimLease) {
        return new TaskReviewService(
                taskReviewPendingRepository,
                taskRuntime,
                turnEngine,
                Clock.systemUTC(),
                claimLease);
    }
}
