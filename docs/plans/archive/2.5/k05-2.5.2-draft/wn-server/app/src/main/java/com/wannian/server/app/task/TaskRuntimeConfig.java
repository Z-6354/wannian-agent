package com.wannian.server.app.task;

import com.wannian.server.app.persistence.SqliteBackgroundTaskRepository;
import com.wannian.server.app.persistence.SqliteSubAgentRunRepository;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.ScheduleResolver;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.TaskRuntime;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 2.5.2 Task 模块装配：Resolver / Runtime / 两 Repo。
 *
 * <p>优先独立 Config，避免膨胀 SqliteConfig（实施单 §2.2）。本号不接线 Committer / TurnEngine。
 * 不注册全局 {@link Clock} bean，避免影响其它模块；Runtime 内用 {@code Clock.systemUTC()}。
 */
@Configuration
public class TaskRuntimeConfig {

    @Bean
    ScheduleResolver scheduleResolver() {
        return new DefaultScheduleResolver();
    }

    @Bean
    TaskRuntime taskRuntime(ScheduleResolver scheduleResolver) {
        return new DefaultTaskRuntime(scheduleResolver, Clock.systemUTC());
    }

    @Bean
    BackgroundTaskRepository backgroundTaskRepository(DataSource dataSource) {
        return new SqliteBackgroundTaskRepository(dataSource);
    }

    @Bean
    SubAgentRunRepository subAgentRunRepository(DataSource dataSource) {
        return new SqliteSubAgentRunRepository(dataSource);
    }
}
