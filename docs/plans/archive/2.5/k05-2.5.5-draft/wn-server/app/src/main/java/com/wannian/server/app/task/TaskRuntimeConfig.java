package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.persistence.SqliteBackgroundTaskRepository;
import com.wannian.server.app.persistence.SqliteSubAgentRunRepository;
import com.wannian.server.app.persistence.SqliteTaskReviewPendingRepository;
import com.wannian.server.kernel.task.BackgroundConcurrencyGate;
import com.wannian.server.kernel.task.BackgroundPolicy;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.TaskExecutor;
import com.wannian.server.kernel.task.TaskReviewPendingRepository;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.tool.ToolRuntime;
import com.wannian.server.kernel.turn.TurnEngine;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Task 模块装配（2.5.2 prepare + 2.5.4 执行/门闩 + 2.5.5 Policy/审核）。
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
    TaskRuntime taskRuntime(
            ScheduleResolver scheduleResolver,
            DataSource dataSource,
            BackgroundTaskRepository backgroundTaskRepository,
            SubAgentRunRepository subAgentRunRepository,
            TaskExecutor taskExecutor,
            BackgroundConcurrencyGate concurrencyGate,
            ObjectMapper objectMapper,
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
                objectMapper);
    }

    @Bean
    BackgroundDispatchTicker backgroundDispatchTicker(TaskRuntime taskRuntime) {
        return new BackgroundDispatchTicker(taskRuntime);
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
