package com.wannian.server.app.persistence;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskDeliveryId;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.TaskDeliveryPending;
import com.wannian.server.kernel.task.TaskDeliveryPendingRepository;
import com.wannian.server.kernel.task.TaskDeliveryStatus;
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

/** {@link TaskDeliveryPendingRepository} SQLite 实现（V026）。 */
public final class SqliteTaskDeliveryPendingRepository implements TaskDeliveryPendingRepository {

    private final DataSource dataSource;

    public SqliteTaskDeliveryPendingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insert(TaskDeliveryPending pending) {
        Objects.requireNonNull(pending, "pending");
        String sql =
                """
                INSERT INTO task_delivery_pending(
                    id, conversation_id, task_id, terminal_status,
                    payload_json, status, created_at, delivered_at
                ) VALUES (?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pending.deliveryId().asString());
            ps.setString(2, pending.conversationId().asString());
            ps.setString(3, pending.taskId().asString());
            ps.setString(4, pending.terminalStatus().name());
            ps.setString(5, pending.payloadJson());
            ps.setString(6, pending.status().name());
            ps.setString(7, pending.createdAt().toString());
            if (pending.deliveredAt() == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, pending.deliveredAt().toString());
            }
            ps.executeUpdate();
        } catch (SQLException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("unique")) {
                return; // 幂等：已存在同 task+terminal
            }
            throw new IllegalStateException("insert task_delivery_pending 失败", ex);
        }
    }

    @Override
    public Optional<TaskDeliveryPending> findByTaskTerminal(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        String sql =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at
                FROM task_delivery_pending
                WHERE task_id = ? AND terminal_status = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId.asString());
            ps.setString(2, terminalStatus.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("find task_delivery_pending 失败", ex);
        }
    }

    @Override
    public Optional<TaskDeliveryPending> findActiveQueued(
            BackgroundTaskId taskId, BackgroundTaskStatus terminalStatus) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        String sql =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at
                FROM task_delivery_pending
                WHERE task_id = ? AND terminal_status = ? AND status = ?
                ORDER BY created_at ASC
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId.asString());
            ps.setString(2, terminalStatus.name());
            ps.setString(3, TaskDeliveryStatus.QUEUED.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("findActiveQueued task_delivery_pending 失败", ex);
        }
    }

    @Override
    public List<TaskDeliveryPending> listQueued(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        String sql =
                """
                SELECT id, conversation_id, task_id, terminal_status,
                       payload_json, status, created_at, delivered_at
                FROM task_delivery_pending
                WHERE conversation_id = ? AND status = ?
                ORDER BY created_at ASC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            ps.setString(2, TaskDeliveryStatus.QUEUED.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskDeliveryPending> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return List.copyOf(out);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("listQueued task_delivery_pending 失败", ex);
        }
    }

    @Override
    public boolean casDelivered(TaskDeliveryId id, Instant deliveredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        try (Connection connection = dataSource.getConnection()) {
            return casDeliveredOn(connection, id, deliveredAt);
        } catch (SQLException ex) {
            throw new IllegalStateException("casDelivered task_delivery_pending 失败", ex);
        }
    }

    /** 同事务 CAS（flush 用）。 */
    public boolean casDeliveredOn(Connection connection, TaskDeliveryId id, Instant deliveredAt)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        String sql =
                """
                UPDATE task_delivery_pending
                SET status = ?, delivered_at = ?
                WHERE id = ? AND status = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TaskDeliveryStatus.DELIVERED.name());
            ps.setString(2, deliveredAt.toString());
            ps.setString(3, id.asString());
            ps.setString(4, TaskDeliveryStatus.QUEUED.name());
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public boolean casCancelled(TaskDeliveryId id) {
        Objects.requireNonNull(id, "id");
        String sql =
                """
                UPDATE task_delivery_pending
                SET status = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TaskDeliveryStatus.CANCELLED.name());
            ps.setString(2, id.asString());
            ps.setString(3, TaskDeliveryStatus.QUEUED.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casCancelled task_delivery_pending 失败", ex);
        }
    }

    private static TaskDeliveryPending mapRow(ResultSet rs) throws SQLException {
        String delivered = rs.getString("delivered_at");
        return new TaskDeliveryPending(
                TaskDeliveryId.parse(rs.getString("id")),
                new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                new BackgroundTaskId(UUID.fromString(rs.getString("task_id"))),
                BackgroundTaskStatus.valueOf(rs.getString("terminal_status")),
                rs.getString("payload_json"),
                TaskDeliveryStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                delivered == null || delivered.isBlank() ? null : Instant.parse(delivered));
    }
}
