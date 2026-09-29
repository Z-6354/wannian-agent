package com.wannian.server.kernel.task;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code sub_agent_run} 持久化（P2 §8）。
 *
 * <p>本号建表不插行；接口签名齐备，默认实现可抛未启用或空实现。
 * 真插入/租约在 2.5.4。
 */
public interface SubAgentRunRepository {

    /**
     * 插入一次 Run 尝试（{@code UNIQUE(task_id, attempt_no)}）。
     *
     * @param connection 非空事务连接
     * @param taskId 非空
     * @param runId 非空
     * @param attemptNo ≥ 1
     * @param now 时间戳
     */
    void insertCreated(
            Connection connection,
            BackgroundTaskId taskId,
            SubAgentRunId runId,
            int attemptNo,
            Instant now);

    /** 行是否存在；本号可恒 {@code false}。后号可改为返回完整 Run 快照。 */
    boolean exists(SubAgentRunId runId);

    /** 按 Task 查最新 attempt 的 Run id；本号可恒 empty。 */
    Optional<SubAgentRunId> findLatestByTaskId(BackgroundTaskId taskId);
}
