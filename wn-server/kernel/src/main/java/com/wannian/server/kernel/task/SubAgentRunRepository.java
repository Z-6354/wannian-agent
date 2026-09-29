package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code sub_agent_run} 持久化（2.5.2 插入 + 2.5.4 lease/CAS）。
 */
public interface SubAgentRunRepository {

    void insertCreated(
            Connection connection,
            BackgroundTaskId taskId,
            SubAgentRunId runId,
            int attemptNo,
            Instant now);

    boolean exists(SubAgentRunId runId);

    Optional<SubAgentRunId> findLatestByTaskId(BackgroundTaskId taskId);

    Optional<SubAgentRunSnapshot> findById(SubAgentRunId runId);

    void attachLease(
            Connection connection,
            SubAgentRunId runId,
            String tokenHash,
            Instant expiresAt,
            Instant now);

    boolean casStatus(
            Connection connection,
            SubAgentRunId runId,
            SubAgentRunStatus expected,
            SubAgentRunStatus next,
            Instant now);

    boolean casStatusWithTokenHash(
            Connection connection,
            SubAgentRunId runId,
            String expectedTokenHash,
            SubAgentRunStatus expected,
            SubAgentRunStatus next,
            Instant now);

    /** 过期未终态 → {@code LOST}；返回标记条数。 */
    int markLostExpired(Connection connection, Instant now);

    /** {@code LEASED|RUNNING} 计数。 */
    int countActive();

    void writeTerminal(
            Connection connection,
            SubAgentRunId runId,
            SubAgentRunStatus terminal,
            String resultJson,
            String errorCode,
            Instant now);
}
