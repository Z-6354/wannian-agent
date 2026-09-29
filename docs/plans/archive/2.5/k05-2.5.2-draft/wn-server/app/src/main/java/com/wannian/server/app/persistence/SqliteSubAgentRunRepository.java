package com.wannian.server.app.persistence;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.SubAgentRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link SubAgentRunRepository} SQLite 实现（P2 §8）。
 *
 * <p>本号可 INSERT/exists/findLatest；真租约与状态机在 2.5.4。插入初始 {@link SubAgentRunStatus#CREATED}，
 * {@code executor_id=local-primary}（kernel-reference v2 单核约定）。
 */
public final class SqliteSubAgentRunRepository implements SubAgentRunRepository {

    public static final String LOCAL_EXECUTOR_ID = "local-primary";

    private final DataSource dataSource;

    public SqliteSubAgentRunRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insertCreated(
            Connection connection,
            BackgroundTaskId taskId,
            SubAgentRunId runId,
            int attemptNo,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(now, "now");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 须 ≥ 1");
        }
        String nowText = now.toString();
        String sql =
                """
                INSERT INTO sub_agent_run(
                    id, task_id, attempt_no, status, executor_id,
                    lease_token_hash, lease_expires_at, result_json, evidence_json, error_code,
                    created_at, updated_at
                ) VALUES (?,?,?,?,?,NULL,NULL,NULL,NULL,NULL,?,?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, runId.asString());
            ps.setString(2, taskId.asString());
            ps.setInt(3, attemptNo);
            ps.setString(4, SubAgentRunStatus.CREATED.name());
            ps.setString(5, LOCAL_EXECUTOR_ID);
            ps.setString(6, nowText);
            ps.setString(7, nowText);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "插入 sub_agent_run 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public boolean exists(SubAgentRunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT 1 FROM sub_agent_run WHERE id = ? LIMIT 1")) {
            ps.setString(1, runId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("查询 sub_agent_run 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public Optional<SubAgentRunId> findLatestByTaskId(BackgroundTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id FROM sub_agent_run
                                WHERE task_id = ?
                                ORDER BY attempt_no DESC
                                LIMIT 1
                                """)) {
            ps.setString(1, taskId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SubAgentRunId(UUID.fromString(rs.getString("id"))));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "按 task 查最新 Run 失败: " + taskId.asString(), ex);
        }
    }
}
