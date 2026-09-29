package com.wannian.server.app.persistence;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link BackgroundTaskRepository} SQLite 实现。
 *
 * <p>{@code schedule_spec_json} 经 {@link ScheduleSpecJson} 编解码（与 FrozenPlan 共用）。
 */
public final class SqliteBackgroundTaskRepository implements BackgroundTaskRepository {

    private final DataSource dataSource;

    public SqliteBackgroundTaskRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insertFromDraft(Connection connection, TaskDraft draft, Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(now, "now");
        String nowText = now.toString();
        String sql =
                """
                INSERT INTO background_task(
                    id, origin_turn_id, conversation_id, companion_id,
                    source, task_type, status, input_json, result_json,
                    notify_policy, retry_policy_json, schedule_spec_json,
                    timezone, next_fire_at, last_fired_at,
                    revision, created_at, updated_at, completed_at
                ) VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,NULL,0,?,?,NULL)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, draft.taskId().asString());
            ps.setString(2, draft.originTurnId().asString());
            ps.setString(3, draft.conversationId().asString());
            if (draft.companionId() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, draft.companionId().value());
            }
            ps.setString(5, draft.source().name());
            ps.setString(6, draft.taskType().name());
            ps.setString(7, draft.initialStatus().name());
            ps.setString(8, draft.inputJson());
            ps.setString(9, draft.notifyPolicy().name());
            ps.setString(10, draft.retryPolicyJson());
            if (draft.scheduleSpec() == null) {
                ps.setNull(11, Types.VARCHAR);
            } else {
                ps.setString(11, ScheduleSpecJson.toJson(draft.scheduleSpec()));
            }
            ps.setString(12, draft.timezone());
            if (draft.nextFireAt() == null) {
                ps.setNull(13, Types.VARCHAR);
            } else {
                ps.setString(13, draft.nextFireAt().toString());
            }
            ps.setString(14, nowText);
            ps.setString(15, nowText);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "插入 background_task 失败: " + draft.taskId().asString(), ex);
        }
    }

    @Override
    public Optional<TaskDraft> findById(BackgroundTaskId id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id, origin_turn_id, conversation_id, companion_id,
                                       source, task_type, status, input_json,
                                       notify_policy, retry_policy_json, schedule_spec_json,
                                       timezone, next_fire_at
                                FROM background_task WHERE id = ?
                                """)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapDraft(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("加载 background_task 失败: " + id.asString(), ex);
        }
    }

    @Override
    public List<TaskDraft> listByConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        List<TaskDraft> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id, origin_turn_id, conversation_id, companion_id,
                                       source, task_type, status, input_json,
                                       notify_policy, retry_policy_json, schedule_spec_json,
                                       timezone, next_fire_at
                                FROM background_task
                                WHERE conversation_id = ?
                                ORDER BY created_at ASC
                                """)) {
            ps.setString(1, conversationId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapDraft(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "按会话列出 background_task 失败: " + conversationId.asString(), ex);
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<TaskDraft> claimNextExecutable(Connection connection, Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(now, "now");
        String nowText = now.toString();
        try (PreparedStatement select =
                connection.prepareStatement(
                        """
                        SELECT id FROM background_task
                        WHERE status IN ('CREATED', 'READY')
                        ORDER BY updated_at ASC, id ASC
                        LIMIT 1
                        """)) {
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                BackgroundTaskId id =
                        new BackgroundTaskId(UUID.fromString(rs.getString("id")));
                try (PreparedStatement update =
                        connection.prepareStatement(
                                """
                                UPDATE background_task
                                SET status = ?, updated_at = ?, revision = revision + 1
                                WHERE id = ? AND status IN ('CREATED', 'READY')
                                """)) {
                    update.setString(1, BackgroundTaskStatus.RUNNING.name());
                    update.setString(2, nowText);
                    update.setString(3, id.asString());
                    if (update.executeUpdate() != 1) {
                        return Optional.empty();
                    }
                }
                return findByIdOnConnection(connection, id);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("claimNextExecutable 失败", ex);
        }
    }

    @Override
    public boolean casStatus(
            Connection connection,
            BackgroundTaskId id,
            BackgroundTaskStatus expected,
            BackgroundTaskStatus next,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE background_task
                        SET status = ?, updated_at = ?, revision = revision + 1
                        WHERE id = ? AND status = ?
                        """)) {
            ps.setString(1, next.name());
            ps.setString(2, now.toString());
            ps.setString(3, id.asString());
            ps.setString(4, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casStatus background_task 失败: " + id.asString(), ex);
        }
    }

    @Override
    public void writeResult(
            Connection connection,
            BackgroundTaskId id,
            String resultJson,
            BackgroundTaskStatus terminalStatus,
            Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(now, "now");
        String nowText = now.toString();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        UPDATE background_task
                        SET status = ?, result_json = ?, updated_at = ?, completed_at = ?,
                            revision = revision + 1
                        WHERE id = ?
                        """)) {
            ps.setString(1, terminalStatus.name());
            if (resultJson == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, resultJson);
            }
            ps.setString(3, nowText);
            ps.setString(4, nowText);
            ps.setString(5, id.asString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("writeResult background_task 失败: " + id.asString(), ex);
        }
    }

    @Override
    public int countRunning() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM background_task WHERE status = 'RUNNING'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException ex) {
            throw new IllegalStateException("countRunning background_task 失败", ex);
        }
    }

    private Optional<TaskDraft> findByIdOnConnection(Connection connection, BackgroundTaskId id)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT id, origin_turn_id, conversation_id, companion_id,
                               source, task_type, status, input_json,
                               notify_policy, retry_policy_json, schedule_spec_json,
                               timezone, next_fire_at
                        FROM background_task WHERE id = ?
                        """)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapDraft(rs));
            }
        }
    }

    private static TaskDraft mapDraft(ResultSet rs) throws SQLException {
        String companionRaw = rs.getString("companion_id");
        CompanionIdentity companion =
                companionRaw == null || companionRaw.isBlank()
                        ? null
                        : new CompanionIdentity(companionRaw);
        String specRaw = rs.getString("schedule_spec_json");
        ScheduleSpec spec =
                specRaw == null || specRaw.isBlank() ? null : ScheduleSpecJson.fromJson(specRaw);
        String nextFireRaw = rs.getString("next_fire_at");
        Instant nextFire =
                nextFireRaw == null || nextFireRaw.isBlank() ? null : Instant.parse(nextFireRaw);
        BackgroundTaskStatus status = BackgroundTaskStatus.valueOf(rs.getString("status"));
        return new TaskDraft(
                new BackgroundTaskId(UUID.fromString(rs.getString("id"))),
                new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                new TurnId(UUID.fromString(rs.getString("origin_turn_id"))),
                companion,
                TaskSource.valueOf(rs.getString("source")),
                TaskType.valueOf(rs.getString("task_type")),
                NotifyPolicy.valueOf(rs.getString("notify_policy")),
                rs.getString("input_json"),
                rs.getString("retry_policy_json"),
                spec,
                rs.getString("timezone"),
                nextFire,
                status);
    }

}
