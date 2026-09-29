package com.wannian.server.app.persistence;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.task.SubAgentRunRepository;
import com.wannian.server.kernel.task.SubAgentRunSnapshot;
import com.wannian.server.kernel.task.SubAgentRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link SubAgentRunRepository} SQLite 实现（2.5.2 插入 + 2.5.4 lease/CAS）。
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

    @Override
    public Optional<SubAgentRunSnapshot> findById(SubAgentRunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id, task_id, attempt_no, status, executor_id,
                                       lease_token_hash, lease_expires_at, result_json, error_code
                                FROM sub_agent_run WHERE id = ?
                                """)) {
            ps.setString(1, runId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapSnapshot(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("加载 sub_agent_run 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public void attachLease(
            Connection connection,
            SubAgentRunId runId,
            String tokenHash,
            Instant expiresAt,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE sub_agent_run
                        SET status = ?, lease_token_hash = ?, lease_expires_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """)) {
            ps.setString(1, SubAgentRunStatus.RUNNING.name());
            ps.setString(2, tokenHash);
            ps.setString(3, expiresAt.toString());
            ps.setString(4, now.toString());
            ps.setString(5, runId.asString());
            ps.setString(6, SubAgentRunStatus.CREATED.name());
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("attachLease 失败: " + runId.asString());
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("attachLease SQL 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public boolean casStatus(
            Connection connection,
            SubAgentRunId runId,
            SubAgentRunStatus expected,
            SubAgentRunStatus next,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE sub_agent_run
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """)) {
            ps.setString(1, next.name());
            ps.setString(2, now.toString());
            ps.setString(3, runId.asString());
            ps.setString(4, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casStatus sub_agent_run 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public boolean casStatusWithTokenHash(
            Connection connection,
            SubAgentRunId runId,
            String expectedTokenHash,
            SubAgentRunStatus expected,
            SubAgentRunStatus next,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expectedTokenHash, "expectedTokenHash");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE sub_agent_run
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ? AND lease_token_hash = ?
                        """)) {
            ps.setString(1, next.name());
            ps.setString(2, now.toString());
            ps.setString(3, runId.asString());
            ps.setString(4, expected.name());
            ps.setString(5, expectedTokenHash);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "casStatusWithTokenHash 失败: " + runId.asString(), ex);
        }
    }

    @Override
    public int markLostExpired(Connection connection, Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE sub_agent_run
                        SET status = 'LOST', updated_at = ?
                        WHERE status IN ('CREATED', 'LEASED', 'RUNNING')
                          AND lease_expires_at IS NOT NULL
                          AND julianday(lease_expires_at) <= julianday(?)
                        """)) {
            String nowText = now.toString();
            ps.setString(1, nowText);
            ps.setString(2, nowText);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("markLostExpired 失败", ex);
        }
    }

    @Override
    public int countActive() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM sub_agent_run
                                WHERE status IN ('LEASED', 'RUNNING')
                                """);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException ex) {
            throw new IllegalStateException("countActive sub_agent_run 失败", ex);
        }
    }

    @Override
    public void writeTerminal(
            Connection connection,
            SubAgentRunId runId,
            SubAgentRunStatus terminal,
            String resultJson,
            String errorCode,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE sub_agent_run
                        SET status = ?, result_json = ?, error_code = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
            ps.setString(1, terminal.name());
            if (resultJson == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, resultJson);
            }
            if (errorCode == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, errorCode);
            }
            ps.setString(4, now.toString());
            ps.setString(5, runId.asString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("writeTerminal 失败: " + runId.asString(), ex);
        }
    }

    private static SubAgentRunSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        String expiresRaw = rs.getString("lease_expires_at");
        Instant expires =
                expiresRaw == null || expiresRaw.isBlank() ? null : Instant.parse(expiresRaw);
        return new SubAgentRunSnapshot(
                new SubAgentRunId(UUID.fromString(rs.getString("id"))),
                new BackgroundTaskId(UUID.fromString(rs.getString("task_id"))),
                rs.getInt("attempt_no"),
                SubAgentRunStatus.valueOf(rs.getString("status")),
                rs.getString("executor_id"),
                rs.getString("lease_token_hash"),
                expires,
                rs.getString("result_json"),
                rs.getString("error_code"));
    }
}
